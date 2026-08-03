package dev.codespire.gateway.attention;

import dev.codespire.contract.attention.AttentionView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * The gateway's attention conditions over HTTP.
 *
 * <p>The UI does not use this — it holds {@code /ws/webhook-attention} open instead, so a condition reaches
 * the operator when it changes rather than when a timer next fires. This is kept because a diagnostic
 * surface that answers a plain {@code curl} is worth having on an operations feature: it is how you
 * establish whether the panel is empty because nothing is wrong or because something is broken, and
 * doing that through a socket needs a client.
 *
 * <p>Mounted under the already-proxied {@code /gw/webhook-repos} prefix, so it needs no dev-server or
 * compose proxy rule. The literal {@code /attention} segment wins over the sibling
 * {@code @Path("/{id}")} in {@code WebhookRepoResource} — see the guard test.
 *
 * <p>Viewer-readable, unlike the registry it sits under: a condition row names a registration that
 * needs attention, never its secret.
 */
@Path("/gw/webhook-repos/attention")
@RolesAllowed({"spire-viewer", "spire-admin"})
@Produces(MediaType.APPLICATION_JSON)
public class WebhookAttentionResource {

    @Inject
    WebhookAttentionRows rows;

    @GET
    public List<AttentionView> list() {
        return rows.collect();
    }
}
