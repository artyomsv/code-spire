package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Validates a context provider's credential — on save (a hard {@link #ping} that
 * rejects a bad token up front) and on demand (a {@link #check} that reports
 * reachability + the token owner for the operator's connectivity indicator). Both
 * hit the source's "who am I" endpoint over the same probe. The analog of
 * {@link dev.codespire.orchestrator.llm.LlmKeyValidator} + the SCM {@code whoami}
 * connectivity check. The baseUrl is SSRF-guarded by the resource before this runs.
 *
 * <p>{@code code}'s raw-content APIs have no "who am I" endpoint to parse — a file's bytes carry no
 * account record — so that type's probe and success signal both differ; see {@link #codeProbe} and
 * {@link #isCredentialAccepted}.
 */
@ApplicationScoped
public class ContextKeyValidator {

    private static final Logger LOG = Logger.getLogger(ContextKeyValidator.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Inject
    ObjectMapper mapper;

    private static final String SIGN_IN_PAGE =
            "Reachable, but the provider returned a sign-in page instead of JSON — the token was not accepted. "
                    + "Check the base URL is the provider's API root and the token has REST API access.";

    /** Types whose connectivity check reads a raw file rather than a "who am I" JSON endpoint. */
    private static final Set<String> RAW_CONTENT_TYPES = Set.of("code");

    /**
     * An owner/repo (also valid as GitLab's group/project and Bitbucket's workspace/repo_slug)
     * shaped placeholder no real token could plausibly have access to. Its existence is irrelevant —
     * only the HTTP status is read — so any syntactically valid, near-certainly-absent value works.
     */
    private static final String CODE_CHECK_REPO = "codespire-connectivity-check/placeholder";
    private static final String CODE_CHECK_PATH = "README.md";
    private static final String CODE_CHECK_REF = "main";

    /** Result of a connectivity {@link #check}: owner display name on success; {@code detail} explains a failure. */
    public record CheckOutcome(boolean ok, String account, int status, String detail) {

        /**
         * True for a genuine credential rejection — deliberately excludes 0 (unreachable) and any
         * other non-2xx status (5xx, ...), which are inconclusive rather than proof the credential
         * is bad. The resource uses this to decide whether persisting the outcome as
         * {@code last_check_ok = FALSE} is warranted, instead of re-deriving the status comparison
         * itself.
         *
         * <p>401/403 are explicit refusals. A 2xx that still failed ({@code !ok}) is, by
         * construction of {@link #check}, the sign-in-page case: the provider is reachable and
         * answered 200, but the body wasn't the "who am I" JSON — an SSO/login redirect refusing
         * the token in HTML rather than with a status code. {@code ping()} already treats that the
         * same as an explicit 401/403 (it throws and blocks the save); a compare on {@code status}
         * rather than the detail string, so a later wording change to the message can't silently
         * stop this from firing.
         */
        public boolean isRejected() {
            return status == 401 || status == 403 || (status / 100 == 2 && !ok);
        }
    }

    /** Save-time validation: throws {@link BadRequestException} if the credential is rejected or unreachable. */
    public void ping(String type, String baseUrl, String authKind, String username, String secret) {
        Probe p = probe(type, baseUrl, authKind, username, secret);
        if (p.status() == 401 || p.status() == 403) {
            throw new BadRequestException("The context provider rejected the credential");
        }
        if (p.status() == 0) {
            throw new BadRequestException("Could not reach the context provider to validate the credential");
        }
        if (isCredentialAccepted(type, p)) {
            return;
        }
        if (p.status() / 100 != 2) {
            throw new BadRequestException("The context provider returned an unexpected status (" + p.status() + ")");
        }
        // A 2xx that isn't a JSON "who am I" is a sign-in page — the token was not accepted. Without this,
        // an SSO/login redirect (HTTP 200 HTML) would pass validation and only fail later on a real fetch.
        throw new BadRequestException(SIGN_IN_PAGE);
    }

    /** On-demand connectivity check: never throws for an HTTP/network outcome — returns it structured. */
    public CheckOutcome check(String type, String baseUrl, String authKind, String username, String secret) {
        Probe p = probe(type, baseUrl, authKind, username, secret);
        if (isCredentialAccepted(type, p)) {
            return new CheckOutcome(true, accountFor(type, p), p.status(), null);
        }
        // Log the failure with the technical detail — otherwise a red indicator has no trail in the logs.
        String detail = p.status() == 0 ? "network/TLS failure (see the earlier stack)"
                : p.status() / 100 != 2 ? "HTTP " + p.status()
                : SIGN_IN_PAGE;
        // A 401/403 body can echo the token back (some APIs quote the offending Authorization header
        // in the error message) — never log it. Every other status is diagnostic, not a credential
        // rejection, so its body is still worth the trail.
        String bodyForLog = p.status() == 401 || p.status() == 403 ? "(withheld: auth failure)"
                : bodySnippet(p.body());
        LOG.warnf("Context connectivity check FAILED for %s — status=%d contentType=%s reason=%s body: %s",
                p.host(), p.status(), p.contentType(), detail, bodyForLog);
        String outcomeDetail = p.status() / 100 != 2 ? null : SIGN_IN_PAGE; // resource maps a bad status itself
        return new CheckOutcome(false, null, p.status(), outcomeDetail);
    }

    /**
     * Whether the probe proves the credential works. The raw-content types ({@link #RAW_CONTENT_TYPES})
     * have no JSON "who am I" body to parse, so their signal is mostly the HTTP status: being told
     * truthfully that the placeholder path does not exist (404) means the token was accepted, and
     * 401/403 (handled by the callers before this runs) mean it was refused. Every other type keeps
     * the original signal: a 2xx body that parses into an account name.
     *
     * <p>A raw-content 2xx additionally has to <em>not look like HTML</em>. Accepting any 2xx would
     * discard the one sign-in-page defence the other branch keeps deliberately (see {@link #ping} and
     * {@link #SIGN_IN_PAGE}): a baseUrl pointing at an SSO portal or auth proxy answers 200 HTML to
     * any path, the placeholder probe included, so Check would go green while every real fetch at
     * review time failed with nothing on screen explaining it. This branch cannot demand parseable
     * JSON — the successful answer here is a source file — but "not an HTML page" is the same signal
     * at the resolution a raw-content API allows (M4, PR 63 review).
     */
    private boolean isCredentialAccepted(String type, Probe p) {
        if (RAW_CONTENT_TYPES.contains(type)) {
            return p.status() == 404 || (p.status() / 100 == 2 && !looksLikeHtml(p));
        }
        return p.status() / 100 == 2 && accountFrom(p.body()) != null;
    }

    /** An HTML answer to a raw-file request is a sign-in page, never the file. */
    private static boolean looksLikeHtml(Probe p) {
        String contentType = p.contentType() == null ? "" : p.contentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("html")) {
            return true;
        }
        String body = p.body() == null ? "" : p.body().stripLeading().toLowerCase(Locale.ROOT);
        return body.startsWith("<!doctype html") || body.startsWith("<html");
    }

    /** The token owner's display name, or {@code null} for a raw-content type — there is none to report. */
    private String accountFor(String type, Probe p) {
        return RAW_CONTENT_TYPES.contains(type) ? null : accountFrom(p.body());
    }

    private record Probe(int status, String body, String contentType, String host) {
    }

    /** GET the "who am I" endpoint; {@code status}=0 signals a network/TLS failure (no HTTP status). */
    private Probe probe(String type, String baseUrl, String authKind, String username, String secret) {
        if (RAW_CONTENT_TYPES.contains(type)) {
            return codeProbe(baseUrl, authKind, secret);
        }
        // Each provider's cheap, authenticated, Cloud+Data-Center-portable "who am I" endpoint.
        String whoAmI = switch (type) {
            case "jira" -> "/rest/api/2/myself";
            case "confluence" -> "/rest/api/user/current";
            case "github-issues" -> "/user";
            case "gitlab-issues" -> "/api/v4/user";
            default -> throw new BadRequestException("Unsupported context provider type '" + type + "'");
        };
        URI uri = URI.create(trimTrailingSlash(baseUrl) + whoAmI);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", authHeader(authKind, username, secret))
                .GET()
                .build();
        return send(req, uri.getHost());
    }

    /**
     * The {@code code} type's probe. There is no "who am I" endpoint for a raw-content API, so this
     * instead requests {@link #CODE_CHECK_PATH} from the near-certainly-absent {@link #CODE_CHECK_REPO}
     * and lets {@link #isCredentialAccepted} read the outcome off the status alone: a 404 there proves
     * the token WAS accepted (the platform told us truthfully that it doesn't exist), while 401/403
     * prove it was refused. The platform is inferred from the host — the same heuristic
     * {@code WorkerContextClients.readerFor} applies on the worker side of this registry type, kept
     * independently here because a "code" credential carries no platform field to read instead, and the
     * orchestrator and worker modules do not share code across this boundary.
     */
    private Probe codeProbe(String baseUrl, String authKind, String secret) {
        String trimmed = trimTrailingSlash(baseUrl);
        String platform = codePlatform(trimmed);
        URI uri = URI.create(trimmed + codeCheckPath(platform));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authHeader(authKind, null, secret))
                .GET();
        if ("github".equals(platform)) {
            // Requests raw bytes rather than the default base64-in-JSON envelope, matching
            // GitHubSourceFileReader — though for this status-only probe either would do.
            builder.header("Accept", "application/vnd.github.raw");
        }
        return send(builder.build(), uri.getHost());
    }

    private Probe send(HttpRequest req, String host) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String contentType = res.headers().firstValue("Content-Type").orElse("");
            return new Probe(res.statusCode(), res.body(), contentType, host);
        } catch (Exception e) {
            // Detail stays server-side (no upstream echo); the caller maps status 0 to a safe message.
            LOG.warnf(e, "Context key validation call failed for host %s", host);
            return new Probe(0, null, "", host);
        }
    }

    /**
     * Same host-substring heuristic as {@code WorkerContextClients.readerFor} — see that method's
     * javadoc for why a single generic {@code code} type needs one at all. GitLab and Bitbucket both
     * conventionally publish a host containing their own name; anything else (including a self-managed
     * GitLab that does not) falls through to GitHub, the least predictable of the three hostnames.
     *
     * <p>Package-private so a same-package test can assert the mapping directly. It cannot be reached
     * through {@link #check}: the branch is chosen from the base URL's <em>host</em>, and a test
     * server answers on {@code localhost}, which is the fallback branch — the very gap this method's
     * absence of coverage left open (PR 63 QA review).
     */
    static String codePlatform(String baseUrl) {
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.contains("gitlab")) {
            return "gitlab";
        }
        if (h.contains("bitbucket")) {
            return "bitbucket";
        }
        return "github";
    }

    /**
     * The raw-content route for {@link #CODE_CHECK_REPO}/{@link #CODE_CHECK_PATH}, per platform.
     * Package-private for the same reason as {@link #codePlatform}.
     */
    static String codeCheckPath(String platform) {
        return switch (platform) {
            case "gitlab" -> "/api/v4/projects/" + encode(CODE_CHECK_REPO) + "/repository/files/"
                    + encode(CODE_CHECK_PATH) + "/raw?ref=" + CODE_CHECK_REF;
            case "bitbucket" -> "/repositories/" + CODE_CHECK_REPO + "/src/" + CODE_CHECK_REF + "/" + CODE_CHECK_PATH;
            default -> "/repos/" + CODE_CHECK_REPO + "/contents/" + CODE_CHECK_PATH + "?ref=" + CODE_CHECK_REF;
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Truncated, whitespace-collapsed body excerpt for a log line — enough to spot an HTML sign-in page. */
    private static String bodySnippet(String body) {
        if (body == null || body.isBlank()) {
            return "(empty)";
        }
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300) + "…";
    }

    /** The token owner's display name (falling back to email/accountId), or null if unparseable. */
    private String accountFrom(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode me = mapper.readTree(body);
            // Jira: displayName/emailAddress/name/accountId. Confluence: displayName/publicName/username/email/accountId.
            for (String field : new String[] {
                    "displayName", "publicName", "emailAddress", "email", "name", "username", "accountId"}) {
                String value = me.path(field).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (Exception e) {
            LOG.debugf(e, "Could not parse the context provider's who-am-I response for the account name");
        }
        return null;
    }

    private static String authHeader(String authKind, String username, String secret) {
        if ("bearer".equals(authKind)) {
            return "Bearer " + secret;
        }
        String raw = username + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
