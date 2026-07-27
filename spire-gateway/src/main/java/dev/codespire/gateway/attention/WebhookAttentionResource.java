package dev.codespire.gateway.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;

/**
 * The gateway's own attention conditions, in the shape the UI merges with the orchestrator's.
 * The gateway never reads the orchestrator's schema and vice versa, so each service answers
 * only for state it owns and the UI concatenates the two feeds.
 *
 * <p>Deliberately mounted under the already-proxied {@code /api/webhook-repos} prefix so no
 * dev-server or compose proxy rule has to change. The literal {@code /attention} segment wins
 * over the sibling {@code @Path("/{id}")} in {@code WebhookRepoResource} — see the guard test.
 */
@Path("/api/webhook-repos/attention")
@Produces(MediaType.APPLICATION_JSON)
public class WebhookAttentionResource {

    @Inject
    WebhookRepoRegistry registry;

    @GET
    public List<AttentionView> list() {
        List<AttentionView> rows = new ArrayList<>();
        for (String target : registry.missingSecretTargets()) {
            rows.add(new AttentionView("WEBHOOK_SECRET_MISSING", Severity.WARNING, target,
                    "This webhook registration has no shared secret, so no delivery can be verified.",
                    "/settings/webhooks"));
        }
        for (WebhookRepoRegistry.Rejection rejection : registry.rejecting()) {
            rows.add(new AttentionView("WEBHOOK_DELIVERIES_REJECTED", Severity.WARNING, rejection.target(),
                    rejection.count() + " delivery(s) refused (" + reason(rejection.reason())
                            + "). Rotate the secret and re-save it at the provider.",
                    "/settings/webhooks"));
        }
        return rows;
    }

    /** The closed neutral reason set, as operator-facing text. */
    private static String reason(String stored) {
        return switch (stored == null ? "" : stored) {
            case "bad_signature" -> "signature did not verify — the shared secret does not match";
            case "provider_mismatch" -> "the key is registered for a different provider type";
            case "malformed_payload" -> "the payload could not be understood";
            case "out_of_scope" -> "the payload's repository is outside this registration's scope";
            default -> "refused";
        };
    }
}
