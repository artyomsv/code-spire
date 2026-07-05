package dev.codespire.orchestrator.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.scm.bitbucket.BitbucketCloudClient;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudDiffSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Read-only SCM access for the manual "register PR" endpoint — the orchestrator
 * fetches PR metadata to synthesize a PullRequestEventReceived, so a PR can be
 * registered/reviewed without a webhook.
 *
 * <p>Least-privilege note: this is the ONE place the orchestrator holds SCM
 * credentials, and it is read-only (no CommentSink, no posting). The write path
 * stays in the worker. Defaults to {@code stub}, so tests need no credentials.
 */
@ApplicationScoped
public class OrchestratorScmProducer {

    @ConfigProperty(name = "spire.scm.provider", defaultValue = "stub")
    String provider;

    @ConfigProperty(name = "spire.scm.bitbucket.base-url", defaultValue = "https://api.bitbucket.org/2.0")
    String baseUrl;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-username")
    Optional<String> botUsername;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-app-password")
    Optional<String> botAppPassword;

    @ConfigProperty(name = "spire.scm.bitbucket.bot-account-id")
    Optional<String> botAccountId;

    @Inject
    ObjectMapper mapper;

    @Produces
    @Singleton
    DiffSource diffSource() {
        return switch (provider) {
            case "bitbucket-cloud" -> new BitbucketCloudDiffSource(new BitbucketCloudClient(new BitbucketCloudConfig(
                    baseUrl,
                    required(botUsername, "spire.scm.bitbucket.bot-username"),
                    required(botAppPassword, "spire.scm.bitbucket.bot-app-password"),
                    "unused-by-orchestrator",
                    required(botAccountId, "spire.scm.bitbucket.bot-account-id")), mapper));
            case "stub" -> new StubDiffSource();
            default -> throw new IllegalStateException("Unknown spire.scm.provider '" + provider
                    + "' — expected stub | bitbucket-cloud");
        };
    }

    private static String required(Optional<String> value, String key) {
        return value.filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Config '" + key + "' is required for spire.scm.provider=bitbucket-cloud"));
    }
}
