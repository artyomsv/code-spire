package dev.codespire.orchestrator.operator;

import dev.codespire.contract.port.OperatorOAuth;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.OAuthApp;
import dev.codespire.orchestrator.analytics.OperatorIdentities;
import dev.codespire.orchestrator.security.OidcSubjects;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An operator proving, themselves, which SCM account is theirs (FR-11).
 *
 * <p>Viewer-accessible, and that is the point: this is the one part of the mapping an operator does
 * for their own account. Everything it can produce is a link from the caller's OWN subject to an
 * account the SCM has just confirmed they control, so there is nothing here a viewer could use to
 * make a claim about anybody else — which is exactly what the manual path can do, and why that one
 * stays admin-only.
 *
 * <p>Under {@code /api}, so the orchestrator's session cookie reaches the callback: the SCM returns
 * the operator by a top-level navigation, and a callback the browser could not authenticate would
 * arrive with no idea whose link to write.
 */
@Path("/api/operator-connect")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"spire-viewer", "spire-admin"})
public class OperatorConnectResource {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(OperatorConnectResource.class);

    /** Where the hash-routed dashboard is, once a connect finishes either way. */
    private static final String MY_ACTIVITY = "/#/analytics/me";

    /**
     * One platform's state for the caller.
     *
     * @param configured whether an admin has set up an OAuth app — without one there is nothing to
     *     click, and offering the button anyway would send the operator to a sign-in that refuses
     *     them with an empty client id
     * @param authorId the account already proved, or empty
     */
    public record Connectable(String providerType, boolean configured, boolean linked, String authorId) {
    }

    @Inject
    SecurityIdentity identity;

    @Inject
    ScmOAuthApps apps;

    @Inject
    OperatorConnects connects;

    @Inject
    ConnectStates states;

    @Inject
    OperatorIdentities identities;

    @GET
    public List<Connectable> platforms() {
        String subject = OidcSubjects.of(identity);
        List<OperatorIdentities.Link> mine = identities.forSubject(subject);
        List<String> configured = apps.list().stream().map(ScmOAuthApps.View::providerType).toList();

        List<Connectable> out = new ArrayList<>();
        for (String type : OperatorConnects.SUPPORTED_TYPES.stream().sorted().toList()) {
            Optional<OperatorIdentities.Link> link = mine.stream()
                    .filter(l -> l.providerType().equals(type)).findFirst();
            out.add(new Connectable(type, configured.contains(type), link.isPresent(),
                    link.map(OperatorIdentities.Link::authorId).orElse("")));
        }
        return out;
    }

    /**
     * Sends the operator to their SCM to sign in.
     *
     * <p>A redirect rather than a URL the interface navigates to itself: the whole window has to
     * move, and handing the address back as JSON would only add a step at which the browser could be
     * pointed somewhere else.
     */
    @GET
    @Path("/{type}/start")
    public Response start(@PathParam("type") String type, @Context UriInfo uriInfo) {
        ScmType scmType = ScmType.fromProviderType(type)
                .orElseThrow(() -> new NotFoundException("No such platform: " + type));
        OperatorOAuth oauth = connects.forType(scmType)
                .orElseThrow(() -> new NotFoundException("No connect adapter for " + type));
        OAuthApp app = apps.resolve(scmType)
                .orElseThrow(() -> new NotFoundException(
                        "No OAuth app is configured for " + type + " — an admin sets one up first."));

        String redirectUri = callbackUri(uriInfo, type);
        String state = states.start(OidcSubjects.of(identity), type, redirectUri);
        return Response.seeOther(URI.create(oauth.authorizeUrl(app, state, redirectUri))).build();
    }

    /**
     * The SCM's answer.
     *
     * <p>Two independent checks must agree before anything is written: the state must be one this
     * deployment issued and has not already redeemed, and it must have been issued to the operator
     * whose session presents it. The first stops a replayed callback; the second stops one operator
     * being handed a link to somebody else's account, which would leave them measured as that person
     * with every screen looking normal.
     *
     * <p>Always redirects, never renders. The operator arrived by a top-level navigation from their
     * SCM, so a JSON body would leave them staring at a document; the outcome rides back to the
     * activity screen as a query parameter instead.
     */
    @GET
    @Path("/{type}/callback")
    public Response callback(@PathParam("type") String type,
                             @QueryParam("code") String code,
                             @QueryParam("state") String state,
                             @QueryParam("error") String error) {
        if (error != null && !error.isBlank()) {
            // The operator declined at the consent screen, or the SCM refused. Its words are not
            // echoed into a URL; this outcome vocabulary is fixed and safe to carry.
            return back("declined");
        }
        Optional<ConnectStates.Pending> pending = states.consume(state);
        if (pending.isEmpty()) {
            return back("expired");
        }
        ConnectStates.Pending attempt = pending.get();
        if (!attempt.oidcSubject().equals(OidcSubjects.of(identity)) || !attempt.providerType().equals(type)) {
            return back("mismatch");
        }
        if (code == null || code.isBlank()) {
            return back("nocode");
        }

        ScmType scmType = ScmType.fromProviderType(type).orElse(null);
        Optional<OperatorOAuth> oauth = scmType == null ? Optional.empty() : connects.forType(scmType);
        Optional<OAuthApp> app = scmType == null ? Optional.empty() : apps.resolve(scmType);
        if (oauth.isEmpty() || app.isEmpty()) {
            return back("unconfigured");
        }

        Author author;
        try {
            author = oauth.get().identify(app.get(), code, attempt.redirectUri());
        } catch (RuntimeException refused) {
            // Not surfaced verbatim: an OAuth error response echoes back what was sent, and on this
            // path one of those values is the client secret.
            LOG.warnf("SCM connect failed for %s: %s", type, refused.getClass().getSimpleName());
            return back("refused");
        }
        if (author.providerUserId() == null || author.providerUserId().isBlank()) {
            // A token good enough to call the API but belonging to no user account. A blank id would
            // match every review that recorded no author -- the opposite of proof.
            return back("noaccount");
        }

        identities.link(new OperatorIdentities.Link(attempt.oidcSubject(), type, author.providerUserId()));
        return back("connected");
    }

    /**
     * This deployment's own callback address, as the browser reached it.
     *
     * <p>Derived from the request rather than configured, so it stays right behind a proxy with no
     * second setting to keep in step — and it is the value an admin must register on the OAuth app,
     * which is why {@link ScmOAuthAppResource} reports it back.
     */
    static String callbackUri(UriInfo uriInfo, String type) {
        // Built from the resource class, not from a literal: the class's own @Path is the one that
        // routes the callback, so a literal here could be edited out of step with it and would fail
        // only at the SCM, as a redirect_uri mismatch nothing in this codebase mentions.
        return uriInfo.getBaseUriBuilder()
                .path(OperatorConnectResource.class)
                .path(type).path("callback")
                .build().toString();
    }

    private static Response back(String outcome) {
        return Response.seeOther(URI.create(
                MY_ACTIVITY + "?connect=" + URLEncoder.encode(outcome, StandardCharsets.UTF_8))).build();
    }
}
