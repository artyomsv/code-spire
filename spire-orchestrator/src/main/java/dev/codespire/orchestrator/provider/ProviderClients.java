package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.scm.bitbucket.BitbucketCloudClient;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudDiffSource;
import dev.codespire.scm.github.GitHubClient;
import dev.codespire.scm.github.GitHubConfig;
import dev.codespire.scm.github.GitHubDiffSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds a read-only SCM client for a resolved provider, from its decrypted
 * credentials — the orchestrator's per-provider replacement for the old
 * .env-configured singleton. Bearer token -> Bearer auth; basic -> username +
 * secret. The webhook secret is a placeholder (this client only reads).
 */
@ApplicationScoped
public class ProviderClients {

    @Inject
    ObjectMapper mapper;

    public DiffSource diffSource(ScmProvider provider) {
        return switch (provider.type()) {
            case "bitbucket-cloud" -> new BitbucketCloudDiffSource(new BitbucketCloudClient(bitbucketConfig(provider), mapper));
            case "github" -> new GitHubDiffSource(new GitHubClient(githubConfig(provider), mapper));
            default -> throw new IllegalStateException("Unsupported provider type: " + provider.type());
        };
    }

    private static String botAccountId(ScmProvider p) {
        return p.botAccountId() == null || p.botAccountId().isBlank() ? "unset" : p.botAccountId();
    }

    private static BitbucketCloudConfig bitbucketConfig(ScmProvider p) {
        if ("bearer".equals(p.authKind())) {
            return new BitbucketCloudConfig(p.baseUrl(), null, null, p.secret(), "unused-read-only", botAccountId(p));
        }
        return new BitbucketCloudConfig(p.baseUrl(), p.authUsername(), p.secret(), "unused-read-only", botAccountId(p));
    }

    private static GitHubConfig githubConfig(ScmProvider p) {
        // GitHub is always Bearer; the webhook secret is a read-path placeholder.
        return new GitHubConfig(p.baseUrl(), p.secret(), "unused-read-only", botAccountId(p));
    }
}
