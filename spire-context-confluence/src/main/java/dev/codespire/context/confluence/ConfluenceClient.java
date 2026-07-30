package dev.codespire.context.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Thin read-only HTTP layer over the Confluence REST API ({@code /rest/api/content}
 * — the same path on Cloud, where the wiki root already carries {@code /wiki}, and
 * on Data Center). Storage-format bodies come back as XHTML, stripped to plain text
 * by {@link ConfluenceHtml}, so no macro walker is needed.
 *
 * <p>Transport, host-pinned redirects and the SSRF guard live in {@link PinnedJsonClient}, shared with
 * every other adapter. What stays here is what is actually Confluence's: the auth scheme (Cloud uses
 * basic with the account email, self-managed a bearer PAT) and the base-URL advice in the sign-in hint.
 */
public class ConfluenceClient {

    private final PinnedJsonClient http;

    public ConfluenceClient(ConfluenceConfig config, ObjectMapper mapper) {
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("Confluence API", config.baseUrl(), authHeader(config),
                        Map.of("Accept", "application/json"),
                        "Check the base URL is the Confluence wiki root and the token has REST API access."),
                mapper, ConfluenceApiException::new);
    }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }

    private static String authHeader(ConfluenceConfig config) {
        if ("bearer".equals(config.authKind())) {
            return "Bearer " + config.secret();
        }
        String raw = config.username() + ":" + config.secret();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
