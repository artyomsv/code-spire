package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.orchestrator.factory.MachineAccounts;
import dev.codespire.orchestrator.provider.ServingAccounts.ServingAccount;
import dev.codespire.orchestrator.security.PublicHttpsGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD for registered SCM providers (spire-ui Settings -> Accounts).
 *
 * <p>Admin-only in full, reads included: the listing is an inventory of every repository host and
 * workspace this deployment can reach, and of the bot identity acting in them. No secret is in the
 * payload — that was the earlier reason to let a viewer read it, and it answers a narrower question
 * than whether a viewer should know the deployment's reach.
 */
@Path("/api/providers")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProviderResource {

    private static final Logger LOG = Logger.getLogger(ProviderResource.class);

    private static final Set<String> AUTH_KINDS = Set.of("bearer", "basic");

    // Deliberately not a second list of provider names: a registry that accepts a type
    // ProviderClients cannot build would fail only later, on the first review.
    private static final Set<String> TYPES = ProviderClients.ACCOUNT_TYPES;

    @Inject
    ProviderRegistry registry;

    @Inject
    ProviderIdentityResolver identity;

    @Inject
    ProviderClients clients;

    /**
     * SSRF guard (CWE-918) escape hatch: create/update immediately issues a
     * server-side whoami() to baseUrl, so the strict https + public-address check
     * is fail-closed by default. Only %dev and %test relax it, for the
     * http://localhost WireMock SCMs.
     */
    @ConfigProperty(name = "spire.security.allow-insecure-provider-urls")
    boolean allowInsecureProviderUrls;

    @GET
    public List<ProviderView> list() {
        return registry.list();
    }

    @GET
    @Path("/{id}")
    public ProviderView get(@PathParam("id") String id) {
        return registry.get(uuid(id)).orElseThrow(() -> new NotFoundException("No provider " + id));
    }

    @POST
    @RolesAllowed("spire-admin")
    public Response create(ProviderInput in) {
        in = normalize(in, null);
        validate(in, true);
        ProviderView created;
        try {
            created = registry.create(resolveIdentity(in));
        } catch (ProviderRegistry.AccountConflict e) {
            throw conflict(e.getMessage());
        }
        // resolveIdentity(...) just proved the token works by resolving the bot's identity with it.
        // A secret is required to create (validate() above), so this call always re-validated it.
        registry.recordCheck(UUID.fromString(created.id()), true, null);
        recordScopes(UUID.fromString(created.id()), in);
        return Response.status(Response.Status.CREATED).entity(registry.get(UUID.fromString(created.id())).orElseThrow()).build();
    }

    @PUT
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public ProviderView update(@PathParam("id") String id, ProviderInput in) {
        ProviderView stored = registry.get(uuid(id)).orElseThrow(() -> new NotFoundException("No provider " + id));
        in = normalize(in, stored.role());
        if (!stored.role().equalsIgnoreCase(in.role())) throw conflict("The role is fixed at registration. Register a new account for that role.");
        validate(in, false);
        ProviderView updated;
        try {
            updated = registry.update(uuid(id), resolveIdentity(in))
                    .orElseThrow(() -> new NotFoundException("No provider " + id));
        } catch (ProviderRegistry.RoleIsFixedAtRegistration | ProviderRegistry.AccountConflict e) {
            throw conflict(e.getMessage());
        }
        // Only record when a secret was actually supplied: that's the only case resolveIdentity(...)
        // re-validates the token (it returns the input untouched on a blank secret). Recording success
        // unconditionally would silently clear a real prior rejection on an update that never touched
        // the credential at all.
        if (in.secret() != null && !in.secret().isBlank()) {
            registry.recordCheck(uuid(id), true, null);
            recordScopes(uuid(id), in);
        }
        return registry.get(uuid(id)).orElseThrow();
    }

    /**
     * When a token is supplied, validate it against the SCM and auto-fill the bot
     * account id from the token owner if the operator left it blank. On update with
     * no new token (keeping the stored one) there is nothing new to validate, so the
     * input is passed through untouched.
     */
    ProviderInput resolveIdentity(ProviderInput in) {
        if (in.secret() == null || in.secret().isBlank()) {
            return in;
        }
        Author owner;
        try {
            owner = identity.resolveForRegistration(in);
        } catch (RuntimeException e) {
            // Generic message to the client: the adapter's root cause may reflect
            // internal/upstream responses (SSRF probe echoes). Detail stays server-side.
            LOG.warnf(e, "Token validation against provider type %s failed", in.type());
            throw new BadRequestException("Could not validate the API token against the provider");
        }
        // A validated token is the authority on who the bot is, so the identity it resolved WINS over
        // whatever the form carried. Preferring the submitted id meant rotating to a different bot
        // account kept the previous account's id while the username changed — and the ADR-013 self-loop
        // guard compares a comment's author against that id, so the bot stopped recognizing its own
        // comments and answered itself. The submitted id is the fallback for providers that cannot tell
        // us (Bitbucket access tokens cannot call /user), where the operator supplies it by hand.
        String resolvedId = owner.providerUserId();
        String botId = resolvedId != null && !resolvedId.isBlank() ? resolvedId : in.botAccountId();
        String botUsername = owner.username(); // resolved login for @-mention matching ("" for synthetic bots)
        // Every component, by name. The 12-argument convenience constructor was used here once and
        // silently set role = null (REVIEWER), so a FACTORY registration through this endpoint was
        // stored as the workspace's reviewer — the review pipeline held the push token and
        // POST /api/runs answered 409 forever. The trap CLAUDE.md records for ReviewResult, again.
        return new ProviderInput(in.name(), in.type(), in.baseUrl(), in.workspace(), in.authKind(),
                in.authUsername(), in.secret(), botId, in.enabled(), in.authors(),
                botUsername, in.conversationLevel(), in.role());
    }

    /**
     * Which accounts serve a (forge type, workspace), per role — for the Repositories screen.
     *
     * <p>Reads the same rows through the same rules the pipeline applies. An enabled REVIEWER row is
     * {@code ok} when its bot identity resolved and {@code no-identity} otherwise — the condition
     * under which {@code ConversationSaga} skips every follow-up. An enabled FACTORY row is
     * {@code ok} exactly when it satisfies the predicate {@link MachineAccounts#resolve} applies,
     * and {@code no-login} otherwise — the case {@code POST /api/runs} answers 409 for. The
     * predicate, not the resolution: resolving decrypts the push credential, which this display
     * read has no use for and which turns an undecryptable ciphertext into a 500 on a page load.
     * A registered but disabled row is {@code disabled}, not {@code missing}: the two have
     * different cures.
     *
     * <p>An unknown forge type is refused rather than answered. Every role would read
     * {@code missing} for a type no adapter serves, which names a cure — register an account —
     * that the operator cannot carry out.
     */
    @GET
    @Path("/serving")
    public ServingAccounts serving(@QueryParam("type") String type, @QueryParam("workspace") String workspace) {
        requireField(type, "type");
        requireField(workspace, "workspace");
        if (!ProviderClients.SUPPORTED_TYPES.contains(type)) {
            throw new BadRequestException("Unsupported provider type '" + type
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        ServingAccount reviewer = registry.registration(type, workspace, ProviderRole.REVIEWER)
                .map(v -> !v.enabled() ? ServingAccount.of("disabled", v)
                        : isBlank(v.botAccountId()) ? ServingAccount.of("no-identity", v)
                        : ServingAccount.of("ok", v))
                .orElseGet(ServingAccount::missing);
        ServingAccount factory = registry.registration(type, workspace, ProviderRole.FACTORY)
                .map(v -> !v.enabled() ? ServingAccount.of("disabled", v)
                        : MachineAccounts.canAuthenticateAPush(v.botUsername()) ? ServingAccount.of("ok", v)
                        : ServingAccount.of("no-login", v))
                .orElseGet(ServingAccount::missing);
        return new ServingAccounts(type, workspace, reviewer, factory);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Live connectivity check: contact the SCM with the provider's stored token
     * ({@code whoami}) and report whether it works, so the operator can confirm a
     * newly-added provider is reachable and authorised without waiting for the
     * first review. The token is never returned; only a category of the failure.
     */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{id}/check")
    @Consumes(MediaType.WILDCARD) // no request body — don't require a JSON content type
    public CheckResult check(@PathParam("id") String id) {
        ScmProvider provider = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No provider " + id));
        registry.recordScopes(provider.id(), clients.reportedScopes(provider.type(), provider.baseUrl(), provider.authKind(),
                provider.authUsername(), provider.secret(), provider.workspace()));
        try {
            Author owner = identity.resolveForCheck(provider);
            registry.recordCheck(provider.id(), true, null);
            return checkResult(provider.id(), true, owner.username(), null);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Provider connectivity check failed for %s (type %s)", id, provider.type());
            String detail = reason(e);
            // Only a genuine authentication rejection may write FALSE: a network error, a 5xx, or
            // any other inconclusive failure is not proof the credential is bad, and recording it
            // as FALSE would light up the row for a transient outage that fixing the network could
            // never clear. isUnauthorized() is deliberately 401-only (see its Javadoc) — a 403 is
            // not treated as a dead credential here either.
            if (e instanceof ScmApiException api && api.isUnauthorized()) {
                registry.recordCheck(provider.id(), false, detail);
            }
            return checkResult(provider.id(), false, null, detail);
        }
    }

    /** Result of {@link #check}: {@code account} on success, a safe {@code detail} on failure. */
    public record CheckResult(boolean ok, String account, String detail, String reportedScopes, java.time.Instant scopesCheckedAt) {
    }

    private CheckResult checkResult(UUID id, boolean ok, String account, String detail) {
        var view = registry.get(id).orElseThrow();
        return new CheckResult(ok, account, detail, view.reportedScopes(), view.scopesCheckedAt());
    }

    /**
     * Confirm a specific repository exists and is reachable with the provider's stored token — a
     * pre-flight for webhook registration. No webhook is created; only a category of the failure is
     * returned. Repo scope only; org reachability is covered by {@link #check}.
     */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{id}/verify-repo")
    public RepoCheck verifyRepo(@PathParam("id") String id, VerifyRepoRequest req) {
        RepoRef repo = parseRepo(req);
        ScmProvider provider = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No provider " + id));
        try {
            clients.diffSource(provider).assertRepoAccessible(repo);
            return new RepoCheck(true, null);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Repo verify failed for %s (type %s) repo %s", id, provider.type(), repo.full());
            return new RepoCheck(false,
                    reasonWithNotFound(e, "Repository not found, or the token cannot see it (HTTP 404)."));
        }
    }

    /** The repository to verify: a full {@code owner/repo} path (repo scope). */
    public record VerifyRepoRequest(String repo) {
    }

    /** Result of {@link #verifyRepo}: {@code detail} carries the failure reason (null when ok). */
    public record RepoCheck(boolean ok, String detail) {
    }

    private static RepoRef parseRepo(VerifyRepoRequest req) {
        String repo = req == null || req.repo() == null ? "" : req.repo().trim();
        int slash = repo.indexOf('/');
        if (slash <= 0 || slash >= repo.length() - 1 || repo.indexOf('/', slash + 1) != -1) {
            throw new BadRequestException("repo must be 'owner/repo'");
        }
        return new RepoRef(repo.substring(0, slash), repo.substring(slash + 1));
    }

    /** A non-leaky, actionable reason — status codes are safe; upstream bodies are not echoed. */
    private static String reason(RuntimeException e) {
        return reasonWithNotFound(e, "Not found (HTTP 404) — check the base URL.");
    }

    private static String reasonWithNotFound(RuntimeException e, String notFound) {
        if (e instanceof ScmApiException api) {
            int status = api.status();
            if (status == 401 || status == 403) {
                return "Authentication failed (HTTP " + status + ") — check the token and its scopes.";
            }
            if (status == 404) {
                return notFound;
            }
            if (status == 429) {
                return "Rate limited (HTTP 429) — try again shortly.";
            }
            return "Provider returned HTTP " + status + ".";
        }
        return "Could not reach the provider (network or TLS error).";
    }

    @DELETE
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        try {
            if (!registry.delete(uuid(id))) throw new NotFoundException("No provider " + id);
        } catch (ProviderRegistry.AccountConflict e) {
            throw conflict(e.getMessage());
        }
        return Response.noContent().build();
    }

    private ProviderInput normalize(ProviderInput in, String storedRole) {
        if (in == null) throw new BadRequestException("Provider body is required");
        String role = in.role();
        if (isBlank(role)) role = storedRole != null ? storedRole
                : ProviderClients.SUPPORTED_TYPES.contains(in.type() == null ? "" : in.type()) ? "REVIEWER" : "CONTEXT";
        String workspace = isBlank(in.workspace()) ? null : in.workspace();
        return new ProviderInput(in.name(), in.type(), in.baseUrl(), workspace, in.authKind(), in.authUsername(),
                in.secret(), in.botAccountId(), in.enabled(), in.authors(), in.botUsername(), in.conversationLevel(), role);
    }

    private void recordScopes(UUID id, ProviderInput in) {
        registry.recordScopes(id, clients.reportedScopes(in.type(), in.baseUrl(), in.authKind(), in.authUsername(),
                in.secret(), in.workspace()));
    }

    private void validate(ProviderInput in, boolean creating) {
        if (in == null) {
            throw new BadRequestException("Provider body is required");
        }
        requireField(in.name(), "name");
        requireField(in.type(), "type");
        requireField(in.baseUrl(), "baseUrl");

        if (in.role() != null && !in.role().isBlank()) {
            // Checked here as well as in the registry, so a closed-set value the client got wrong is
            // a 400 naming the set rather than the registry's IllegalArgumentException as a 500 —
            // the same doubling LlmProviderResource does for the model, for the same reason.
            try {
                ProviderRole.of(in.role());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unsupported provider role '" + in.role()
                        + "' (expected one of: CONTEXT, FACTORY, REVIEWER)");
            }
        }
        if (!TYPES.contains(in.type())) {
            throw new BadRequestException("Unsupported provider type '" + in.type()
                    + "' (expected one of: " + String.join(", ", TYPES.stream().sorted().toList()) + ")");
        }
        ProviderRole role = ProviderRole.of(in.role());
        if (role == ProviderRole.CONTEXT) {
            if (in.workspace() != null && !in.workspace().isBlank()) throw new BadRequestException("A context-only account has no workspace");
        } else {
            if (!ProviderClients.SUPPORTED_TYPES.contains(in.type())) throw new BadRequestException("This kind supports only the CONTEXT role");
            requireField(in.workspace(), "workspace");
        }
        validateBaseUrl(in.baseUrl());
        if (in.authKind() == null || !AUTH_KINDS.contains(in.authKind())) {
            throw new BadRequestException("authKind must be 'bearer' or 'basic'");
        }
        if ("basic".equals(in.authKind()) && (in.authUsername() == null || in.authUsername().isBlank())) {
            throw new BadRequestException("authUsername is required for basic auth");
        }
        if (creating && (in.secret() == null || in.secret().isBlank())) {
            throw new BadRequestException("secret (API token) is required");
        }
    }

    /**
     * SSRF guard (CWE-918): the baseUrl is dereferenced server-side right after
     * validation (whoami), so it must be an https URL resolving to a public
     * address. Delegates to the shared {@link PublicHttpsGuard} (also used by the
     * LLM key validation), so the two server-side-fetch paths cannot drift.
     */
    void validateBaseUrl(String baseUrl) {
        PublicHttpsGuard.validate(baseUrl, allowInsecureProviderUrls);
    }

    private static void requireField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required");
        }
    }

    /**
     * The message goes on the RESPONSE, not the exception: {@code new ClientErrorException(message,
     * status)} leaves the body empty, so the client learns nothing about what to send instead.
     * Same shape as {@code RunResource.conflict}.
     */
    private static ClientErrorException conflict(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.CONFLICT).entity(message).build());
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid provider id");
        }
    }
}
