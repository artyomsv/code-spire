package dev.codespire.context.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.*;
import java.util.Map;

/** Shared authentication and pinned transport; context readers expose no write operations. */
public final class JiraApiConnection {
    private JiraApiConnection() {}
    public static PinnedJsonClient reader(JiraConfig config, ObjectMapper mapper) {
        return new PinnedJsonClient(transport(config), mapper, JiraApiException::new);
    }
    public static PinnedJsonWriter writer(JiraConfig config, ObjectMapper mapper) {
        return new PinnedJsonWriter(transport(config), mapper, JiraApiException::new);
    }
    private static PinnedJsonConfig transport(JiraConfig config) {
        return new PinnedJsonConfig("Jira API", config.baseUrl(), "bearer".equals(config.authKind()) ? "Bearer " + config.secret() : "Basic " + java.util.Base64.getEncoder().encodeToString((config.username() + ":" + config.secret()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Map.of("Accept", "application/json"), "Check the Jira site root and the selected account REST permissions.");
    }
}
