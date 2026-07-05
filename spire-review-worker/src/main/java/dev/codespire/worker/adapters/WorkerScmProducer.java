package dev.codespire.worker.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.scm.bitbucket.BitbucketCloudClient;
import dev.codespire.scm.bitbucket.BitbucketCloudCommentSink;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudDiffSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Worker-side SCM selection: DiffSource + CommentSink (the worker never
 * receives webhooks — ingress lives in the gateway). ADR-001 fail-fast:
 * a selected provider with missing config refuses to start, naming the key.
 * The webhook secret is deliberately NOT read here — least privilege.
 */
@ApplicationScoped
public class WorkerScmProducer {

    @ConfigProperty(name = "spire.scm.provider")
    String provider;

    @ConfigProperty(name = "spire.scm.bitbucket.base-url", defaultValue = "https://api.bitbucket.org/2.0")
    String baseUrl;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-username")
    Optional<String> botUsername;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-app-password")
    Optional<String> botAppPassword;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-account-id")
    Optional<String> botAccountId;

    @ConfigProperty(name = "spire.scm.bitbucket.api-token")
    Optional<String> apiToken;

    @Inject
    ObjectMapper mapper;

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
        // The worker has no webhook secret, so a placeholder satisfies the config
        // invariant without granting one. Auth is a Bearer access token when set,
        // else Basic (username + app password / Atlassian API token).
        String accountId = required(botAccountId, "spire.scm.bitbucket.bot-account-id");
        BitbucketCloudConfig config = apiToken.filter(t -> !t.isBlank()).isPresent()
                ? new BitbucketCloudConfig(baseUrl, botUsername.orElse(null), botAppPassword.orElse(null),
                        apiToken.get(), "unused-by-worker", accountId)
                : new BitbucketCloudConfig(baseUrl,
                        required(botUsername, "spire.scm.bitbucket.bot-username"),
                        required(botAppPassword, "spire.scm.bitbucket.bot-app-password"),
                        "unused-by-worker", accountId);
        return new BitbucketCloudClient(config, mapper);
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
