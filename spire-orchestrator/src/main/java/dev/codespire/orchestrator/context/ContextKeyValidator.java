package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Validates a context provider's credential — on save (a hard {@link #ping} that
 * rejects a bad token up front) and on demand (a {@link #check} that reports
 * reachability + the token owner for the operator's connectivity indicator). Both
 * hit the source's "who am I" endpoint over the same probe. The analog of
 * {@link dev.codespire.orchestrator.llm.LlmKeyValidator} + the SCM {@code whoami}
 * connectivity check. The baseUrl is SSRF-guarded by the resource before this runs.
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

    /** Result of a connectivity {@link #check}: owner display name on success; {@code detail} explains a failure. */
    public record CheckOutcome(boolean ok, String account, int status, String detail) {
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
        if (p.status() / 100 != 2) {
            throw new BadRequestException("The context provider returned an unexpected status (" + p.status() + ")");
        }
        // A 2xx that isn't a JSON "who am I" is a sign-in page — the token was not accepted. Without this,
        // an SSO/login redirect (HTTP 200 HTML) would pass validation and only fail later on a real fetch.
        if (accountFrom(p.body()) == null) {
            throw new BadRequestException(SIGN_IN_PAGE);
        }
    }

    /** On-demand connectivity check: never throws for an HTTP/network outcome — returns it structured. */
    public CheckOutcome check(String type, String baseUrl, String authKind, String username, String secret) {
        Probe p = probe(type, baseUrl, authKind, username, secret);
        if (p.status() / 100 == 2 && accountFrom(p.body()) != null) {
            return new CheckOutcome(true, accountFrom(p.body()), p.status(), null);
        }
        // Log the failure with the technical detail — otherwise a red indicator has no trail in the logs.
        String detail = p.status() == 0 ? "network/TLS failure (see the earlier stack)"
                : p.status() / 100 != 2 ? "HTTP " + p.status()
                : SIGN_IN_PAGE;
        LOG.warnf("Context connectivity check FAILED for %s — status=%d contentType=%s reason=%s body: %s",
                p.host(), p.status(), p.contentType(), detail, bodySnippet(p.body()));
        String outcomeDetail = p.status() / 100 != 2 ? null : SIGN_IN_PAGE; // resource maps a bad status itself
        return new CheckOutcome(false, null, p.status(), outcomeDetail);
    }

    private record Probe(int status, String body, String contentType, String host) {
    }

    /** GET the "who am I" endpoint; {@code status}=0 signals a network/TLS failure (no HTTP status). */
    private Probe probe(String type, String baseUrl, String authKind, String username, String secret) {
        // Each provider's cheap, authenticated, Cloud+Data-Center-portable "who am I" endpoint.
        String whoAmI = switch (type) {
            case "jira" -> "/rest/api/2/myself";
            case "confluence" -> "/rest/api/user/current";
            default -> throw new BadRequestException("Unsupported context provider type '" + type + "'");
        };
        URI uri = URI.create(trimTrailingSlash(baseUrl) + whoAmI);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", authHeader(authKind, username, secret))
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String contentType = res.headers().firstValue("Content-Type").orElse("");
            return new Probe(res.statusCode(), res.body(), contentType, uri.getHost());
        } catch (Exception e) {
            // Detail stays server-side (no upstream echo); the caller maps status 0 to a safe message.
            LOG.warnf(e, "Context key validation call failed for host %s", uri.getHost());
            return new Probe(0, null, "", uri.getHost());
        }
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
