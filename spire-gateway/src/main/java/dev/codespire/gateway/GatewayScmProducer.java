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
 * Gateway-side SCM selection: only the INGRESS port lives here (the gateway
 * never reads diffs or posts comments). ADR-001 fail-fast: a selected provider
 * with missing config refuses to start, naming the exact key.
 */
@ApplicationScoped
public class GatewayScmProducer {

    /** Registered manual commands (CONTRACT §10). Derived from Capability beans at P2. */
    private static final Set<String> COMMANDS = Set.of("review");

    @ConfigProperty(name = "spire.scm.provider")
    String provider;

    @ConfigProperty(name = "spire.scm.bitbucket.base-url", defaultValue = "https://api.bitbucket.org/2.0")
    String baseUrl;

    @ConfigProperty(name = "spire.scm.bitbucket.webhook-secret")
    Optional<String> webhookSecret;

    @Inject
    ObjectMapper mapper;

    @Produces
    @Singleton
    ScmIngress ingress() {
        return switch (provider) {
            // Least privilege (security finding): the internet-facing gateway
            // holds ONLY the webhook secret. It never calls the Bitbucket API, so
            // it is never handed the bot App Password — placeholders satisfy the
            // config invariant. The self-loop guard (bot-authored events) now runs
            // in the orchestrator against the provider registry, so the gateway no
            // longer needs the bot account id either.
            case "bitbucket-cloud" -> new BitbucketCloudIngress(new BitbucketCloudConfig(
                    baseUrl,
                    "unused-by-gateway",
                    "unused-by-gateway",
                    required(webhookSecret, "spire.scm.bitbucket.webhook-secret")),
                    mapper, COMMANDS);
            case "stub" -> new RejectingIngress();
            default -> throw new IllegalStateException("Unknown spire.scm.provider '" + provider
                    + "' — expected stub | bitbucket-cloud");
        };
    }

    private static String required(Optional<String> value, String key) {
        return value.filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Config '" + key + "' is required for spire.scm.provider=bitbucket-cloud"));
    }

    /** stub mode: webhooks are meaningless without a real SCM — reject them all. */
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
