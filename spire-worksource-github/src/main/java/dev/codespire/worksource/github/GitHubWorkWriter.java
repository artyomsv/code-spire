package dev.codespire.worksource.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.context.github.GitHubApiConnection;
import dev.codespire.context.github.GitHubIssueConfig;
import dev.codespire.http.PinnedJsonWriter;
import dev.codespire.worksource.WorkSourceException;
import java.util.Map;

/** The work arm's write facade; no write method is added to the context provider. */
final class GitHubWorkWriter {
    private final PinnedJsonWriter http;
    private final ObjectMapper mapper;
    private final String scope;
    GitHubWorkWriter(GitHubIssueConfig config, ObjectMapper mapper, String scope) {
        this.http = GitHubApiConnection.writer(config, mapper);
        this.mapper = mapper;
        this.scope = scope;
    }
    String comment(String number, String text, String effectId) {
        try {
            String body = dev.codespire.worksource.WorkEffectMarker.body(text, effectId);
            JsonNode response = http.post("/repos/" + scope + "/issues/" + number + "/comments", mapper.writeValueAsString(Map.of("body", body)));
            JsonNode id = response.path("id");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1) throw new WorkSourceException("Comment acknowledgement has no stable identity.");
            return id.asText();
        } catch (Exception failure) { throw new WorkSourceException("GitHub comment capability is unavailable; check the selected account's Issues write access."); }
    }
    void transition(String number, String issueId, String state) {
        if (!"open".equals(state) && !"closed".equals(state)) throw new WorkSourceException("GitHub supports only open/closed issue transitions.");
        try {
            JsonNode response = http.patch("/repos/" + scope + "/issues/" + number, mapper.writeValueAsString(Map.of("state", state)));
            if (!issueId.equals(response.path("id").asText()) || !state.equals(response.path("state").asText()))
                throw new WorkSourceException("Transition acknowledgement did not match the requested issue and state.");
        } catch (Exception failure) { throw new WorkSourceException("GitHub transition capability is unavailable; check the selected account's Issues write access."); }
    }
}
