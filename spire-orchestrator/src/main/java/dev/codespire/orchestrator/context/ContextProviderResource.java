package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.confluence.ConfluenceConfig;
import dev.codespire.context.confluence.ConfluenceContextProvider;
import dev.codespire.context.confluence.ConfluenceLinks;
import dev.codespire.context.github.GitHubIssueConfig;
import dev.codespire.context.github.GitHubIssueContextProvider;
import dev.codespire.context.github.GitHubIssueRefs;
import dev.codespire.context.gitlab.GitLabIssueConfig;
import dev.codespire.context.gitlab.GitLabIssueContextProvider;
import dev.codespire.context.gitlab.GitLabIssueRefs;
import dev.codespire.context.jira.JiraConfig;
import dev.codespire.context.jira.JiraContextProvider;
import dev.codespire.context.jira.JiraTicketKeys;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.security.PublicHttpsGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** CRUD for registered context providers (spire-ui Settings -> Context). */
@Path("/api/context-providers")
@RolesAllowed({"spire-viewer", "spire-admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContextProviderResource {

    private static final Logger LOG = Logger.getLogger(ContextProviderResource.class);
    private static final Set<String> TYPES =
            Set.of("jira", "confluence", "github-issues", "gitlab-issues");
    private static final Set<String> AUTH_KINDS = Set.of("basic", "bearer");

    /**
     * Types whose API accepts only a bearer token. GitHub's basic auth is deprecated and a GitLab
     * personal access token works on the OAuth-compliant {@code Authorization} header, so accepting
     * {@code basic} here would only let an operator save something the worker has to refuse later —
     * failing the context step with nothing on screen to explain why.
     */
    private static final Set<String> BEARER_ONLY_TYPES = Set.of("github-issues", "gitlab-issues");

    /**
     * Preview resolves one reference with no pull request behind it, so a bare {@code #123} has no
     * repository to belong to. Saying which two inputs DO work turns a dead end into a next step.
     * Per-type wording: GitHub operators think in repositories, GitLab operators in projects — using
     * one vocabulary for both would hand a GitLab operator the wrong noun and the wrong example.
     */
    static final String GITHUB_BARE_REFERENCE_GUIDANCE =
            "A bare #123 needs a repository — enter the qualified form (owner/repo#123) or paste the "
                    + "issue URL.";

    static final String GITLAB_BARE_ISSUE_GUIDANCE =
            "A bare #123 needs a project — enter the qualified form (group/project#123) or paste the "
                    + "issue URL.";

    /**
     * GitLab spells three objects with three sigils, and only the issue has a qualified short form.
     * A merge request must be named by URL; an epic is scoped to a GROUP, not a project, so
     * {@code group/project#123} is not merely the wrong example for it but an unreachable one.
     * Sending an operator to a form that cannot resolve is worse than saying nothing.
     */
    static final String GITLAB_BARE_MERGE_REQUEST_GUIDANCE =
            "A bare !123 needs a project — paste the merge request URL "
                    + "(https://<host>/group/project/-/merge_requests/123). Merge requests have no "
                    + "qualified short form.";

    static final String GITLAB_BARE_EPIC_GUIDANCE =
            "A bare &123 needs a group — paste the epic URL "
                    + "(https://<host>/groups/<group>/-/epics/123). Epics are group-scoped, so there "
                    + "is no group/project form for them.";

    @Inject
    ContextProviderRegistry registry;

    @Inject
    ContextKeyValidator validator;

    @Inject
    ObjectMapper mapper;

    /** Mirrors the LLM/SCM resources: fail-closed https+public-address check, relaxed only in %dev/%test. */
    @ConfigProperty(name = "spire.security.allow-insecure-provider-urls")
    boolean allowInsecureProviderUrls;

    @GET
    public List<ContextProviderView> list() {
        return registry.list();
    }

    @GET
    @Path("/{id}")
    public ContextProviderView get(@PathParam("id") String id) {
        return registry.get(uuid(id)).orElseThrow(() -> new NotFoundException("No context provider " + id));
    }

    @POST
    @RolesAllowed("spire-admin")
    public Response create(ContextProviderInput in) {
        validate(in, true);
        validator.ping(in.type(), in.baseUrl(), in.authKind(), in.username(), in.secret());
        ContextProviderView created = registry.create(in);
        // validator.ping(...) just proved the credential works; a secret is required to create
        // (validate() above), so this call always re-validated it.
        registry.recordCheck(UUID.fromString(created.id()), true, null);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public ContextProviderView update(@PathParam("id") String id, ContextProviderInput in) {
        validate(in, false);
        // Validate the credential only when a new one is supplied (blank = keep the stored secret).
        boolean rotatingSecret = in.secret() != null && !in.secret().isBlank();
        if (rotatingSecret) {
            validator.ping(in.type(), in.baseUrl(), in.authKind(), in.username(), in.secret());
        }
        ContextProviderView updated = registry.update(uuid(id), in)
                .orElseThrow(() -> new NotFoundException("No context provider " + id));
        // Only record when a secret was actually supplied and pinged: that's the only case that
        // re-validated the credential. Recording success unconditionally would silently clear a
        // real prior rejection on an update that never touched the credential at all (mirrors
        // ProviderResource.update).
        if (rotatingSecret) {
            registry.recordCheck(uuid(id), true, null);
        }
        return updated;
    }

    @DELETE
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!registry.delete(uuid(id))) {
            throw new NotFoundException("No context provider " + id);
        }
        return Response.noContent().build();
    }

    /**
     * Live connectivity check: contact the source with the provider's stored
     * credential ({@code /myself}) and report whether it works, so the operator can
     * confirm a newly-added provider is reachable and authorised without waiting for
     * the first review. The secret is never returned; only a category of the failure.
     */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{id}/check")
    @Consumes(MediaType.WILDCARD) // no request body — don't require a JSON content type
    public CheckResult check(@PathParam("id") String id) {
        ContextProviderConfig cfg = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No context provider " + id));
        ContextKeyValidator.CheckOutcome out =
                validator.check(cfg.type(), cfg.baseUrl(), cfg.authKind(), cfg.username(), cfg.secret());
        if (out.ok()) {
            registry.recordCheck(cfg.id(), true, null);
            return new CheckResult(true, out.account(), null);
        }
        // A specific detail (e.g. a sign-in page on a 200) beats the status-only category.
        String detail = out.detail() != null ? out.detail() : reason(out.status());
        LOG.warnf("Context provider %s (%s) connectivity check failed: %s", id, cfg.type(), detail);
        // Only a genuine authentication rejection may write FALSE — an unreachable provider
        // (status 0), a 5xx, or any other inconclusive failure is not proof the credential is bad,
        // and recording it as FALSE would light up the row for a transient outage that fixing the
        // network could never clear.
        if (out.isRejected()) {
            registry.recordCheck(cfg.id(), false, detail);
        }
        return new CheckResult(false, null, detail);
    }

    /** Result of {@link #check}: {@code account} on success, a safe {@code detail} on failure. */
    public record CheckResult(boolean ok, String account, String detail) {
    }

    /**
     * Test the integration end to end: take the operator's input (a Jira ticket number/key or a Confluence
     * page URL/id), resolve it the way a real review would, fetch it live, and return exactly the
     * {@link ContextItem}s a review would inject — so the operator can preview the context content before
     * it ever reaches an LLM.
     */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{id}/preview")
    public PreviewResult preview(@PathParam("id") String id, PreviewRequest body) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new BadRequestException("text is required");
        }
        ContextProviderConfig cfg = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No context provider " + id));
        return switch (cfg.type()) {
            case "jira" -> previewJira(cfg, body.text());
            case "confluence" -> previewConfluence(cfg, body.text());
            case "github-issues" -> previewGitHubIssues(cfg, body.text());
            case "gitlab-issues" -> previewGitLabIssues(cfg, body.text());
            default -> throw new BadRequestException("Preview is not supported for type '" + cfg.type() + "'");
        };
    }

    private PreviewResult previewJira(ContextProviderConfig cfg, String text) {
        Set<String> projectKeys = JiraTicketKeys.parseProjectKeys(cfg.projectKeys());
        Set<String> keys = JiraTicketKeys.resolvePreview(text, projectKeys);
        if (keys.isEmpty()) {
            // No key resolved — tell the operator why (usually a bare number with no project key set).
            return new PreviewResult(List.of(), "EMPTY", List.of(),
                    projectKeys.isEmpty()
                            ? "No issue key found in the input. Enter a full key (PROJ-123), or set project keys to look up a bare number."
                            : "No issue key matched the configured project keys.");
        }
        ContextProvider provider = new JiraContextProvider(
                new JiraConfig(cfg.baseUrl(), cfg.authKind(), cfg.username(), cfg.secret(), projectKeys), mapper);
        // Jira keys are globally unique within a site, so the review's platform is irrelevant here.
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                keys, Set.of(), null, null); // preview has no repository, so no rules
        return runPreview(cfg, provider, req, List.copyOf(keys),
                "Jira did not return the ticket(s) as JSON — run the connection check; the token is likely "
                        + "being redirected to a sign-in page (wrong base URL, or the token lacks REST access).",
                "Could not reach Jira to resolve the ticket(s).");
    }

    private PreviewResult previewConfluence(ContextProviderConfig cfg, String text) {
        Set<String> pageIds = ConfluenceLinks.resolvePreview(text, cfg.baseUrl());
        if (pageIds.isEmpty()) {
            return new PreviewResult(List.of(), "EMPTY", List.of(),
                    "No Confluence page found in the input. Paste a page URL (…/pages/12345/…) or a bare page id.");
        }
        ContextProvider provider = new ConfluenceContextProvider(
                new ConfluenceConfig(cfg.baseUrl(), cfg.authKind(), cfg.username(), cfg.secret(),
                        ConfluenceLinks.parseSpaceKeys(cfg.projectKeys())), mapper);
        // The provider narrows links to its own host, so feed the resolved ids back as host-local page URLs.
        String siteBase = cfg.baseUrl().replaceAll("/$", "");
        List<String> links = pageIds.stream()
                .map(pid -> siteBase + "/pages/viewpage.action?pageId=" + pid).toList();
        // scmType is irrelevant here: a Confluence page id is globally unique on its host, not
        // repo-relative, so the review's platform has nothing to disambiguate.
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                Set.copyOf(links), Set.of(), null, null);
        return runPreview(cfg, provider, req, List.copyOf(pageIds),
                "Confluence did not return the page(s) as JSON — run the connection check; the token is likely "
                        + "being redirected to a sign-in page (wrong base URL, or the token lacks REST access).",
                "Could not reach Confluence to resolve the page(s).");
    }

    private PreviewResult previewGitHubIssues(ContextProviderConfig cfg, String text) {
        Set<String> references = GitHubIssueRefs.candidates(text);
        boolean anyQualified = references.stream()
                .map(GitHubIssueRefs::parse)
                .flatMap(Optional::stream)
                .anyMatch(ref -> !ref.isRepoRelative());
        if (!anyQualified) {
            return new PreviewResult(List.of(), "EMPTY", List.of(),
                    references.isEmpty()
                            ? "No issue reference found in the input. Enter owner/repo#123 or paste an issue URL."
                            : GITHUB_BARE_REFERENCE_GUIDANCE);
        }
        ContextProvider provider = new GitHubIssueContextProvider(
                new GitHubIssueConfig(cfg.baseUrl(), cfg.authKind(), cfg.secret(),
                        GitHubIssueRefs.parseRepoAllowList(cfg.projectKeys())), mapper);
        // The operator is explicitly testing THIS provider, so the request states its own platform —
        // a preview with a null platform would make every repo-relative reference decline.
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                references, Set.of(), ScmType.GITHUB, null);
        return runPreview(cfg, provider, req, List.copyOf(references),
                "GitHub did not return the issue as JSON — run the connection check; the token is likely "
                        + "being redirected to a sign-in page (wrong base URL, or the token cannot read issues).",
                "Could not reach GitHub to resolve the reference(s).");
    }

    private PreviewResult previewGitLabIssues(ContextProviderConfig cfg, String text) {
        Set<String> references = GitLabIssueRefs.candidates(text);
        boolean anyQualified = references.stream()
                .map(GitLabIssueRefs::parse)
                .flatMap(Optional::stream)
                .anyMatch(ref -> !ref.isProjectRelative());
        if (!anyQualified) {
            return new PreviewResult(List.of(), "EMPTY", List.of(),
                    references.isEmpty()
                            ? "No reference found in the input. Enter group/project#123 or paste an issue URL."
                            : gitLabBareGuidance(references));
        }
        ContextProvider provider = new GitLabIssueContextProvider(
                new GitLabIssueConfig(cfg.baseUrl(), cfg.authKind(), cfg.secret(),
                        GitLabIssueRefs.parseProjectAllowList(cfg.projectKeys())), mapper);
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                references, Set.of(), ScmType.GITLAB, null);
        return runPreview(cfg, provider, req, List.copyOf(references),
                "GitLab did not return the issue as JSON — run the connection check; the token is likely "
                        + "being redirected to a sign-in page (wrong base URL, or the token lacks read_api).",
                "Could not reach GitLab to resolve the reference(s).");
    }

    /**
     * The guidance for a bare GitLab reference, in the syntax of the sigil the operator actually
     * typed. Mixed input gets every relevant hint rather than an arbitrary one.
     */
    private static String gitLabBareGuidance(Set<String> references) {
        Set<String> hints = new LinkedHashSet<>();
        for (String reference : references) {
            GitLabIssueRefs.parse(reference)
                    .filter(GitLabIssueRefs.Ref::isProjectRelative)
                    .ifPresent(ref -> hints.add(gitLabGuidanceFor(ref.kind())));
        }
        // Nothing parsed as project-relative (a qualified form would have short-circuited earlier),
        // so the issue wording is the safe general answer.
        return hints.isEmpty() ? GITLAB_BARE_ISSUE_GUIDANCE : String.join(" ", hints);
    }

    private static String gitLabGuidanceFor(GitLabIssueRefs.Kind kind) {
        return switch (kind) {
            case ISSUE -> GITLAB_BARE_ISSUE_GUIDANCE;
            case MERGE_REQUEST -> GITLAB_BARE_MERGE_REQUEST_GUIDANCE;
            case EPIC -> GITLAB_BARE_EPIC_GUIDANCE;
        };
    }

    /** Run a provider's {@code contribute} for a preview and map its outcome to a {@link PreviewResult}. */
    private PreviewResult runPreview(ContextProviderConfig cfg, ContextProvider provider, ContextRequest req,
                                     List<String> keys, String errorOnErrorStatus, String errorOnThrow) {
        try {
            ContextContribution c = provider.contribute(req).toCompletableFuture().join();
            // An EMPTY here means the reference WAS recognised and attempted — unlike the bare-form
            // short-circuits above, which never reach a provider. Returning it with a null detail
            // made "no such issue" and "the token cannot see it" look identical on screen.
            String detail = switch (c.status()) {
                case ERROR -> errorOnErrorStatus;
                case EMPTY -> "Nothing came back for " + String.join(", ", keys)
                        + " — check that it exists and that the token can read it.";
                case OK -> null;
            };
            return new PreviewResult(keys, c.status().name(), c.items(), detail);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Context preview failed for provider %s (%s)", cfg.id(), cfg.type());
            return new PreviewResult(keys, "ERROR", List.of(), errorOnThrow);
        }
    }

    /** Preview input: a Jira ticket number/key, a Confluence page URL/id, or free text to resolve from. */
    public record PreviewRequest(String text) {
    }

    /** Preview output: the keys that resolved, the fetch status, the items, and a note when empty/errored. */
    public record PreviewResult(List<String> keys, String status, List<ContextItem> items, String detail) {
    }

    /** A non-leaky, actionable reason — status codes are safe; upstream bodies are not echoed. */
    private static String reason(int status) {
        if (status == 401 || status == 403) {
            return "Authentication failed (HTTP " + status + ") — check the token and its scopes.";
        }
        if (status == 404) {
            return "Not found (HTTP 404) — check the base URL.";
        }
        if (status == 429) {
            return "Rate limited (HTTP 429) — try again shortly.";
        }
        if (status == 0) {
            return "Could not reach the provider (network or TLS error).";
        }
        return "Provider returned HTTP " + status + ".";
    }

    private void validate(ContextProviderInput in, boolean creating) {
        if (in == null) {
            throw new BadRequestException("Context provider body is required");
        }
        requireField(in.name(), "name");
        requireField(in.type(), "type");
        requireField(in.baseUrl(), "baseUrl");
        requireField(in.authKind(), "authKind");
        if (!TYPES.contains(in.type())) {
            throw new BadRequestException("Unsupported context provider type '" + in.type()
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        if (!AUTH_KINDS.contains(in.authKind())) {
            throw new BadRequestException("Unsupported authKind '" + in.authKind()
                    + "' (expected one of: " + String.join(", ", AUTH_KINDS.stream().sorted().toList()) + ")");
        }
        if (BEARER_ONLY_TYPES.contains(in.type()) && !"bearer".equals(in.authKind())) {
            throw new BadRequestException("Context provider type '" + in.type()
                    + "' requires authKind 'bearer' (a personal access token). Basic auth is not "
                    + "supported for this type.");
        }
        // Basic auth (Jira Cloud: email + API token) needs a username; bearer (PAT) does not.
        if ("basic".equals(in.authKind())) {
            requireField(in.username(), "username");
        }
        // SSRF guard: the baseUrl is dereferenced server-side (credential ping) and later by the worker.
        PublicHttpsGuard.validate(in.baseUrl(), allowInsecureProviderUrls);
        if (creating && (in.secret() == null || in.secret().isBlank())) {
            throw new BadRequestException("secret is required");
        }
    }

    private static void requireField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required");
        }
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid context provider id");
        }
    }
}
