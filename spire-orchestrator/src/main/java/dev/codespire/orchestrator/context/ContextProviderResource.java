package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.confluence.ConfluenceConfig;
import dev.codespire.context.confluence.ConfluenceContextProvider;
import dev.codespire.context.confluence.ConfluenceLinks;
import dev.codespire.context.jira.JiraConfig;
import dev.codespire.context.jira.JiraContextProvider;
import dev.codespire.context.jira.JiraTicketKeys;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.security.PublicHttpsGuard;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** CRUD for registered context providers (spire-ui Settings -> Context). */
@Path("/api/context-providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContextProviderResource {

    private static final Logger LOG = Logger.getLogger(ContextProviderResource.class);
    private static final Set<String> TYPES = Set.of("jira", "confluence");
    private static final Set<String> AUTH_KINDS = Set.of("basic", "bearer");

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
    public Response create(ContextProviderInput in) {
        validate(in, true);
        validator.ping(in.type(), in.baseUrl(), in.authKind(), in.username(), in.secret());
        return Response.status(Response.Status.CREATED).entity(registry.create(in)).build();
    }

    @PUT
    @Path("/{id}")
    public ContextProviderView update(@PathParam("id") String id, ContextProviderInput in) {
        validate(in, false);
        // Validate the credential only when a new one is supplied (blank = keep the stored secret).
        if (in.secret() != null && !in.secret().isBlank()) {
            validator.ping(in.type(), in.baseUrl(), in.authKind(), in.username(), in.secret());
        }
        return registry.update(uuid(id), in)
                .orElseThrow(() -> new NotFoundException("No context provider " + id));
    }

    @DELETE
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
    @Path("/{id}/check")
    @Consumes(MediaType.WILDCARD) // no request body — don't require a JSON content type
    public CheckResult check(@PathParam("id") String id) {
        ContextProviderConfig cfg = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No context provider " + id));
        ContextKeyValidator.CheckOutcome out =
                validator.check(cfg.type(), cfg.baseUrl(), cfg.authKind(), cfg.username(), cfg.secret());
        if (out.ok()) {
            return new CheckResult(true, out.account(), null);
        }
        // A specific detail (e.g. a sign-in page on a 200) beats the status-only category.
        String detail = out.detail() != null ? out.detail() : reason(out.status());
        LOG.warnf("Context provider %s (%s) connectivity check failed: %s", id, cfg.type(), detail);
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
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                keys, List.of(), Set.of());
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
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                Set.of(), links, Set.of());
        return runPreview(cfg, provider, req, List.copyOf(pageIds),
                "Confluence did not return the page(s) as JSON — run the connection check; the token is likely "
                        + "being redirected to a sign-in page (wrong base URL, or the token lacks REST access).",
                "Could not reach Confluence to resolve the page(s).");
    }

    /** Run a provider's {@code contribute} for a preview and map its outcome to a {@link PreviewResult}. */
    private PreviewResult runPreview(ContextProviderConfig cfg, ContextProvider provider, ContextRequest req,
                                     List<String> keys, String errorOnErrorStatus, String errorOnThrow) {
        try {
            ContextContribution c = provider.contribute(req).toCompletableFuture().join();
            String status = c.status().name();
            String detail = "ERROR".equals(status) ? errorOnErrorStatus : null;
            return new PreviewResult(keys, status, c.items(), detail);
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
