package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.IdentitySource;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.scm.bitbucket.BitbucketCloudClient;
import dev.codespire.scm.bitbucket.BitbucketCloudCommentSink;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudDiffSource;
import dev.codespire.scm.bitbucket.BitbucketCloudPullRequestSink;
import dev.codespire.scm.github.GitHubClient;
import dev.codespire.scm.github.GitHubCommentSink;
import dev.codespire.scm.github.GitHubConfig;
import dev.codespire.scm.github.GitHubDiffSource;
import dev.codespire.scm.github.GitHubPullRequestSink;
import dev.codespire.scm.gitlab.GitLabClient;
import dev.codespire.scm.gitlab.GitLabCommentSink;
import dev.codespire.scm.gitlab.GitLabConfig;
import dev.codespire.scm.gitlab.GitLabDiffSource;
import dev.codespire.scm.gitlab.GitLabPullRequestSink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Builds a read-only SCM client for a resolved provider, from its decrypted
 * credentials — the orchestrator's per-provider replacement for the old
 * .env-configured singleton. Bearer token -> Bearer auth; basic -> username +
 * secret. The webhook secret is a placeholder (this client only reads).
 */
@ApplicationScoped
public class ProviderClients {

    /**
     * The provider types this build can actually construct clients for — the registry's
     * validation list. It lives beside the switches below so that "which types exist"
     * cannot drift from "which types we can build", and so callers that only need to
     * validate an input never name a provider themselves. Narrower than {@link ScmType},
     * which also declares types no adapter implements yet.
     */
    public static final Set<String> SUPPORTED_TYPES = Set.of("bitbucket-cloud", "github", "gitlab");

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
     * A read-only thread reader for a resolved provider — re-fetches a comment thread's full
     * messages from the SCM on demand (ADR-011: conversation text is never persisted, only re-fetched
     * by reference). All three SCMs' comment sinks implement {@link ThreadSource}; an unknown type
     * throws so the caller degrades gracefully (falls back to the stored preview).
     */
    public ThreadSource threadSource(ScmProvider provider) {
        // The registry's bot identity is passed straight in, so the review detail's thread re-fetch
        // attributes the bot's turns without a live GET /user (matched per API: login/username/account id).
        return switch (provider.type()) {
            case "github" -> new GitHubCommentSink(
                    new GitHubClient(githubConfig(provider), mapper), provider.botUsername());
            case "bitbucket-cloud" -> new BitbucketCloudCommentSink(
                    new BitbucketCloudClient(bitbucketConfig(provider), mapper), provider.botAccountId());
            case "gitlab" -> new GitLabCommentSink(
                    new GitLabClient(gitlabConfig(provider), mapper), provider.botUsername());
            default -> throw new UnsupportedOperationException(
                    "Thread re-fetch is not supported for provider type: " + provider.type());
        };
    }

    /**
     * A client that can OPEN a pull request for a resolved provider (M2, SCM-MAPPING §8).
     *
     * <p><b>The provider handed in must be the FACTORY-role account</b>, resolved through
     * {@code MachineAccounts}, never the reviewer. This method cannot check that — an
     * {@code ScmProvider} carries a decrypted secret and a type, and the role is part of the
     * lookup KEY rather than of the row that comes back. A branch pushed as one account with a
     * pull request opened by another is a pull request nobody can attribute, and the reviewer's
     * own author allowlist would then skip it: work produced and reviewed by no one.
     *
     * <p>All three forges are supported, so unlike {@code threadSource} there is no degraded
     * path — a fourth provider type cannot open a pull request at all, and pretending otherwise
     * would record a run as delivered with nothing behind it.
     */
    public PullRequestSink pullRequestSink(ScmProvider provider) {
        return switch (provider.type()) {
            case "github" -> new GitHubPullRequestSink(new GitHubClient(githubConfig(provider), mapper));
            case "bitbucket-cloud" -> new BitbucketCloudPullRequestSink(
                    new BitbucketCloudClient(bitbucketConfig(provider), mapper));
            case "gitlab" -> new GitLabPullRequestSink(new GitLabClient(gitlabConfig(provider), mapper));
            default -> throw new IllegalStateException(
                    "Cannot open a pull request on provider type: " + provider.type());
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
