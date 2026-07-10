package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.contract.port.ScmType;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudIngress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Gateway-side ingress wiring: only the INGRESS port lives here (the gateway never
 * reads diffs or posts comments). Which SCMs are reviewed is the UI registry, not
 * config — this produces ONLY the LEGACY single-secret Bitbucket edge
 * (/webhooks/bitbucket), which turns on iff its webhook secret is configured. The
 * per-repo registry edges (e.g. /webhooks/github/{key}) are separate resources and
 * depend on none of this.
 */
@ApplicationScoped
public class GatewayScmProducer {

    /** Registered manual commands (CONTRACT §10). Derived from Capability beans at P2. */
    private static final Set<String> COMMANDS = Set.of("review");

    @ConfigProperty(name = "spire.scm.bitbucket.base-url", defaultValue = "https://api.bitbucket.org/2.0")
    String baseUrl;

    @ConfigProperty(name = "spire.scm.bitbucket.webhook-secret")
    Optional<String> webhookSecret;

    @Inject
    ObjectMapper mapper;

    @Produces
    @Singleton
    ScmIngress ingress() {
        // The legacy Bitbucket edge is enabled purely by the presence of its webhook
        // secret — no provider flag. Least privilege: the internet-facing gateway
        // holds ONLY the webhook secret; it never calls the Bitbucket API, so it is
        // never handed the bot App Password (placeholders satisfy the config
        // invariant). The self-loop guard runs downstream in the orchestrator.
        return webhookSecret.filter(s -> !s.isBlank())
                .<ScmIngress>map(secret -> new BitbucketCloudIngress(
                        new BitbucketCloudConfig(baseUrl, "unused-by-gateway", "unused-by-gateway", secret),
                        mapper, COMMANDS))
                .orElseGet(RejectingIngress::new);
    }

    /** No legacy Bitbucket edge configured (no webhook secret) — /webhooks/bitbucket rejects everything. */
    static final class RejectingIngress implements ScmIngress {

        @Override
        public ScmType type() {
            return ScmType.BITBUCKET_CLOUD;
        }

        @Override
        public boolean verifySignature(RawWebhook raw) {
            return false;
        }

        @Override
        public List<IntegrationEvent> translate(RawWebhook raw) {
            return List.of();
        }
    }
}
