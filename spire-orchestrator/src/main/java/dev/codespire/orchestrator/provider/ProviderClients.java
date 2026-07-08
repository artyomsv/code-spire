package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.IdentitySource;
import dev.codespire.scm.bitbucket.BitbucketCloudClient;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudDiffSource;
import dev.codespire.scm.github.GitHubClient;
import dev.codespire.scm.github.GitHubConfig;
import dev.codespire.scm.github.GitHubDiffSource;
import dev.codespire.scm.gitlab.GitLabClient;
import dev.codespire.scm.gitlab.GitLabConfig;
import dev.codespire.scm.gitlab.GitLabDiffSource;
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
            case "gitlab" -> new GitLabDiffSource(new GitLabClient(gitlabConfig(provider), mapper));
            default -> throw new IllegalStateException("Unsupported provider type: " + provider.type());
        };
    }

    /**
     * A read client for a not-yet-registered provider, to resolve/validate the
     * token at create time via {@code whoami()} (before the bot account id is known).
     */
    public IdentitySource identitySource(String type, String baseUrl, String authKind, String authUsername, String secret) {
        return switch (type) {
            case "bitbucket-cloud" -> new BitbucketCloudDiffSource(
                    new BitbucketCloudClient(bitbucketConfig(baseUrl, authKind, authUsername, secret), mapper));
            case "github" -> new GitHubDiffSource(
                    new GitHubClient(githubConfig(baseUrl, secret), mapper));
            case "gitlab" -> new GitLabDiffSource(
                    new GitLabClient(new GitLabConfig(baseUrl, secret), mapper));
            default -> throw new IllegalStateException("Unsupported provider type: " + type);
        };
    }

    private static BitbucketCloudConfig bitbucketConfig(ScmProvider p) {
        return bitbucketConfig(p.baseUrl(), p.authKind(), p.authUsername(), p.secret());
    }

    private static GitHubConfig githubConfig(ScmProvider p) {
        return githubConfig(p.baseUrl(), p.secret());
    }

    private static BitbucketCloudConfig bitbucketConfig(String baseUrl, String authKind, String authUsername,
                                                        String secret) {
        if ("bearer".equals(authKind)) {
            return new BitbucketCloudConfig(baseUrl, null, null, secret, "unused-read-only");
        }
        return new BitbucketCloudConfig(baseUrl, authUsername, secret, "unused-read-only");
    }

    private static GitHubConfig githubConfig(String baseUrl, String secret) {
        // GitHub is always Bearer; the webhook secret is a read-path placeholder.
        return new GitHubConfig(baseUrl, secret, "unused-read-only");
    }

    private static GitLabConfig gitlabConfig(ScmProvider p) {
        // GitLab is always Bearer; it carries no webhook secret on the read path.
        return new GitLabConfig(p.baseUrl(), p.secret());
    }
}
