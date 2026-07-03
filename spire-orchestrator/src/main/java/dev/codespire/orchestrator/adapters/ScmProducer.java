package dev.codespire.orchestrator.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.scm.bitbucket.BitbucketCloudClient;
import dev.codespire.scm.bitbucket.BitbucketCloudCommentSink;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudDiffSource;
import dev.codespire.scm.bitbucket.BitbucketCloudIngress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.Set;

/**
 * SCM provider selection: {@code spire.scm.provider} = stub | bitbucket-cloud.
 * No default in prod — an unset provider fails startup (ADR-001 fail-fast).
 * Adding an SCM = a new case here + a new adapter module; the pipeline never
 * changes.
 */
@ApplicationScoped
public class ScmProducer {

    /** Registered manual commands (CONTRACT §10). Derived from Capability beans at P2. */
    private static final Set<String> COMMANDS = Set.of("review");

    @ConfigProperty(name = "spire.scm.provider")
    String provider;

    @ConfigProperty(name = "spire.scm.bitbucket.base-url", defaultValue = "https://api.bitbucket.org/2.0")
    String baseUrl;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-username")
    Optional<String> botUsername;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-app-password")
    Optional<String> botAppPassword;

    @ConfigProperty(name = "spire.scm.bitbucket.webhook-secret")
    Optional<String> webhookSecret;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-account-id")
    Optional<String> botAccountId;

    @Inject
    ObjectMapper mapper;

    @Produces
    @Singleton
    ScmIngress ingress() {
        return switch (provider) {
            case "bitbucket-cloud" -> new BitbucketCloudIngress(bitbucketConfig(), mapper, COMMANDS);
            case "stub" -> new StubScm.RejectingIngress();
            default -> throw unknownProvider();
        };
    }

    @Produces
    @Singleton
    DiffSource diffSource() {
        return switch (provider) {
            case "bitbucket-cloud" -> new BitbucketCloudDiffSource(client());
            case "stub" -> new StubScm.StubDiffSource();
            default -> throw unknownProvider();
        };
    }

    @Produces
    @Singleton
    CommentSink commentSink() {
        return switch (provider) {
            case "bitbucket-cloud" -> new BitbucketCloudCommentSink(client());
            case "stub" -> new StubScm.LoggingCommentSink();
            default -> throw unknownProvider();
        };
    }

    private BitbucketCloudClient client() {
        return new BitbucketCloudClient(bitbucketConfig(), mapper);
    }

    private BitbucketCloudConfig bitbucketConfig() {
        return new BitbucketCloudConfig(
                baseUrl,
                required(botUsername, "spire.scm.bitbucket.bot-username"),
                required(botAppPassword, "spire.scm.bitbucket.bot-app-password"),
                required(webhookSecret, "spire.scm.bitbucket.webhook-secret"),
                required(botAccountId, "spire.scm.bitbucket.bot-account-id"));
    }

    private static String required(Optional<String> value, String key) {
        return value.filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Config '" + key + "' is required for spire.scm.provider=bitbucket-cloud"));
    }

    private IllegalStateException unknownProvider() {
        return new IllegalStateException("Unknown spire.scm.provider '" + provider
                + "' — expected stub | bitbucket-cloud");
    }
}
