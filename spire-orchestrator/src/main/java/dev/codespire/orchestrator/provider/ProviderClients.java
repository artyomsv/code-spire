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

    /** Account kinds also include credentials for the context-only adapters. */
    public static final Set<String> ACCOUNT_TYPES = Set.of("bitbucket-cloud", "github", "gitlab", "atlassian");

    public static boolean supportsContext(String source, String account) {
        return switch (source) {
            case "jira", "confluence" -> "atlassian".equals(account);
            case "github-issues" -> "github".equals(account);
            case "gitlab-issues" -> "gitlab".equals(account);
            case "code" -> Set.of("github", "gitlab").contains(account);
            default -> false;
        };
    }

    public static boolean supportsContextAuth(String source, String authKind) {
        return switch (source) {
            case "github-issues", "gitlab-issues", "code" -> "bearer".equals(authKind);
            default -> Set.of("basic", "bearer").contains(authKind);
        };
    }

    /** Only the legacy migration guesses a platform; new sources explicitly select an account. */
    public static String legacyContextAccountType(String source, String baseUrl) {
        return switch (source) {
            case "jira", "confluence" -> "atlassian";
            case "github-issues" -> "github";
            case "gitlab-issues" -> "gitlab";
            case "code" -> {
                String host = java.net.URI.create(baseUrl).getHost();
                if (host == null) throw new IllegalArgumentException("Source URL has no host");
                String normalized = host.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("bitbucket")) {
                    throw new IllegalArgumentException("Legacy source needs an explicitly supported account");
                }
                yield normalized.contains("gitlab") ? "gitlab" : "github";
            }
            default -> throw new IllegalArgumentException("Unsupported legacy source type");
        };
    }

    @Inject
    ObjectMapper mapper;

    private final java.net.http.HttpClient accountHttp = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();

    public dev.codespire.contract.scm.Author accountIdentity(String type, String baseUrl, String authKind,
                                                            String username, String secret, String workspace) {
        if (!"atlassian".equals(type)) {
            String apiBase = "gitlab".equals(type) ? gitlabAccountBase(baseUrl) : baseUrl;
            return identitySource(type, apiBase, authKind, username, secret).whoamiOrValidate(workspace);
        }
        String base = baseUrl.replaceAll("/+$", "");
        // A site may have only one product. Try both existing identity endpoints, without redirects.
        String site = base.endsWith("/wiki") ? base.substring(0, base.length() - 5) : base;
        AccountProbeFailure failure = new AccountProbeFailure(0);
        for (String path : java.util.List.of("/rest/api/3/myself", "/wiki/rest/api/user/current")) {
            try {
                var response = accountGet(site + path, authKind, username, secret);
                if (response.statusCode() / 100 != 2) {
                    if (!failure.isUnauthorized()) failure = new AccountProbeFailure(response.statusCode());
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode json;
                try {
                    json = mapper.readTree(response.body());
                } catch (java.io.IOException e) {
                    failure = new AccountProbeFailure(401);
                    continue;
                }
                if (json == null) { failure = new AccountProbeFailure(401); continue; }
                String id = json.path("accountId").asText("");
                String name = json.path("displayName").asText("");
                if (!id.isBlank()) return dev.codespire.contract.scm.Author.of(id, "", name);
                failure = new AccountProbeFailure(401);
            } catch (java.io.IOException e) {
                if (!failure.isUnauthorized()) failure = new AccountProbeFailure(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AccountProbeFailure(0);
            }
        }
        throw failure;
    }

    /** Advisory: missing headers or a failed introspection never invalidate an account. */
    public String reportedScopes(String type, String baseUrl, String authKind, String username,
                                 String secret, String workspace) {
        String base = baseUrl.replaceAll("/+$", "");
        try {
            return switch (type) {
                case "github" -> scopeHeader(accountGet(base + "/user", "bearer", null, secret));
                case "gitlab" -> {
                    var response = accountGet(gitlabAccountBase(base) + "/personal_access_tokens/self", "bearer", null, secret);
                    if (response.statusCode() / 100 != 2) yield null;
                    var scopes = mapper.readTree(response.body()).path("scopes");
                    if (!scopes.isArray()) yield null;
                    var values = new java.util.ArrayList<String>();
                    for (var scope : scopes) {
                        if (!scope.isTextual()) yield null;
                        values.add(scope.asText());
                    }
                    yield String.join(", ", values);
                }
                case "bitbucket-cloud" -> {
                    var response = accountGet(base + "/user", authKind, username, secret);
                    if (response.statusCode() / 100 != 2 && workspace != null) {
                        response = accountGet(base + "/repositories/" + java.net.URLEncoder.encode(workspace,
                                java.nio.charset.StandardCharsets.UTF_8), authKind, username, secret);
                    }
                    yield scopeHeader(response);
                }
                default -> null;
            };
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String gitlabAccountBase(String url) {
        String base = url.replaceAll("/+$", "");
        return base.endsWith("/api/v4") ? base : base + "/api/v4";
    }

    private static String scopeHeader(java.net.http.HttpResponse<String> response) {
        return response.statusCode() / 100 == 2 ? response.headers().firstValue("X-OAuth-Scopes").orElse(null) : null;
    }

    private java.net.http.HttpResponse<String> accountGet(String url, String kind, String username, String secret)
            throws java.io.IOException, InterruptedException {
        String auth = "basic".equals(kind) ? "Basic " + java.util.Base64.getEncoder().encodeToString(
                (username + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : "Bearer " + secret;
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(10)).header("Accept", "application/json")
                .header("Authorization", auth).GET().build();
        return accountHttp.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    public static boolean scopesNeedAttention(String type, ProviderRole role, String reported) {
        if (reported == null || role == ProviderRole.CONTEXT) return false;
        var scopes = new java.util.HashSet<>(java.util.Arrays.asList(reported.split("[,\\s]+")));
        Set<String> expected = switch (type) {
            case "github" -> role == ProviderRole.FACTORY ? Set.of("repo", "public_repo")
                    : Set.of("repo", "public_repo");
            case "gitlab" -> role == ProviderRole.FACTORY ? Set.of("api", "write_repository")
                    : Set.of("api", "read_api", "read_repository");
            case "bitbucket-cloud" -> role == ProviderRole.FACTORY
                    ? Set.of("repository:write", "write:repository:bitbucket")
                    : Set.of("repository", "repository:write", "read:repository:bitbucket", "write:repository:bitbucket");
            default -> Set.of();
        };
        return !expected.isEmpty() && java.util.Collections.disjoint(scopes, expected);
    }

    private static final class AccountProbeFailure extends RuntimeException implements dev.codespire.contract.scm.ScmApiException {
        private final int status;
        AccountProbeFailure(int status) { super("Account identity probe failed (HTTP " + status + ")"); this.status = status; }
        public int status() { return status; }
        public boolean isUnauthorized() { return status == 401 || status == 403; }
    }

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
     * <p><b>The provider must be the FACTORY-role account, and that is now CHECKED.</b> An earlier
     * version of this javadoc said the check was impossible because the role is part of the lookup
     * key rather than of the row. A security review showed the row had it all along —
     * {@code ProviderRegistry.resolve} filters {@code WHERE role = ?} and the mapper simply did not
     * read the column — so the assertion costs one field.
     *
     * <p>The same review corrected WHY it matters. This javadoc used to say the reviewer's author
     * allowlist would skip a pull request the reviewer itself opened. <b>That was wrong:</b>
     * nothing gates pull-request authorship — the bot-authored check covers comments and commands
     * only — and an empty allowlist means everyone, so by default the reviewer WOULD review its
     * own. The real consequences are narrower and still sufficient: the branch is pushed as the
     * factory account, so a pull request opened as the reviewer misattributes the work; the
     * reviewer's token is not provisioned for that write, and its 403 would read as the factory
     * account failing, sending an operator to the wrong account; and an operator who HAS set an
     * allowlist does get the skip.
     *
     * <p>All three forges are supported, so unlike {@code threadSource} there is no degraded
     * path — a fourth provider type cannot open a pull request at all, and pretending otherwise
     * would record a run as delivered with nothing behind it.
     */
    public PullRequestSink pullRequestSink(ScmProvider provider) {
        if (provider.role() != ProviderRole.FACTORY) {
            throw new IllegalArgumentException("a pull request is opened by the FACTORY account; "
                    + "this was handed the " + provider.role() + " provider " + provider.id());
        }
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
