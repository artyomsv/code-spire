package dev.codespire.gateway.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * The gateway's attention conditions, evaluated fresh on each call.
 *
 * <p>Its own bean rather than living in the resource because two callers need the identical rows: the
 * socket that pushes them and the HTTP endpoint kept for diagnosis. Building them twice would let the
 * two drift, and the wording here IS the behaviour — an operator acts on this text.
 *
 * <p>The gateway never reads the orchestrator's schema and vice versa, so each service answers only
 * for state it owns and the UI concatenates the two feeds.
 */
@ApplicationScoped
public class WebhookAttentionRows {

    @Inject
    WebhookRepoRegistry registry;

    public List<AttentionView> collect() {
        List<AttentionView> rows = new ArrayList<>();
        for (WebhookRepoRegistry.Registration reg : registry.missingSecret()) {
            rows.add(new AttentionView("WEBHOOK_SECRET_MISSING", Severity.WARNING,
                    subject(reg.providerType(), reg.target()),
                    "This webhook registration has no shared secret, so no delivery can be verified.",
                    editLink(reg.id())));
        }
        for (WebhookRepoRegistry.Rejection rejection : registry.rejecting()) {
            rows.add(new AttentionView("WEBHOOK_DELIVERIES_REJECTED", Severity.WARNING,
                    subject(rejection.providerType(), rejection.target()),
                    refused(rejection.count()) + ": " + reason(rejection.reason())
                            + ". Rotate the secret here, then re-save it in the webhook settings at "
                            + rejection.providerType() + ".",
                    editLink(rejection.id())));
        }
        return rows;
    }

    /**
     * Deep-links to the one registration that needs changing, rather than to a page the operator
     * then has to search — with three registrations and a repo path that can exist on two
     * providers, "go to Settings" was leaving the reader to work out which row was meant.
     * The UI opens that registration's dialog; rotating the secret stays a deliberate click,
     * because rotating invalidates the live one and a mis-clicked link must not break a
     * working webhook.
     */
    private static String editLink(String id) {
        return "/settings/webhooks?edit=" + id;
    }

    /**
     * How an operator identifies the registration they have to go and fix. A repo path alone is
     * ambiguous — the same workspace name can be registered on two different providers — and it
     * also never says what kind of thing is broken, so a bare {@code owner/repo} left the operator
     * guessing which provider's webhook settings to open. Both parts are database values, so no
     * provider name enters this source.
     */
    private static String subject(String providerType, String target) {
        return providerType + " · " + target;
    }

    /** Operator-facing prose, so a count of one does not read as "1 delivery(s)". */
    private static String refused(int count) {
        return count == 1
                ? "1 webhook delivery was refused"
                : count + " webhook deliveries were refused";
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
