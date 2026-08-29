package dev.codespire.e2e.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives GitLab as a human contributor would: creates users and projects, pushes commits, opens merge
 * requests, comments, replies and merges.
 *
 * <p>Deliberately hand-rolled rather than built on spire-scm-gitlab. A harness that drives the system
 * with the code under test confirms itself: a wrong belief about the API would be shared by the driver
 * and the adapter, and the test would agree with the bug instead of catching it.
 */
public final class GitLabDriver {

    /** Fixed and obviously synthetic, so a run is reproducible and nothing is scraped out of a log. */
    private static final String ROOT_TOKEN = "TEST-e2e-root-token-000000000000";

    private final String token;

    private GitLabDriver(String token) {
        this.token = token;
    }

    public static GitLabDriver asRoot() {
        Rails.run("""
                user = User.find_by_username('root')
                user.personal_access_tokens.where(name: 'e2e-root').delete_all
                token = user.personal_access_tokens.create!(
                  scopes: ['api', 'sudo'], name: 'e2e-root', expires_at: 1.day.from_now)
                token.set_token('%s')
                token.save!
                """.formatted(ROOT_TOKEN));
        return new GitLabDriver(ROOT_TOKEN);
    }

    public static GitLabDriver as(String token) {
        return new GitLabDriver(token);
    }

    // --- users -----------------------------------------------------------------------------------

    public long ensureUser(String username, String email, String password) {
        String output = Rails.run("""
                user = User.find_by_username('%s')
                if user.nil?
                  user = User.new(username: '%s', email: '%s', name: '%s',
                                  password: '%s', password_confirmation: '%s')
                  user.skip_confirmation!
                  user.save!
                end
                puts user.id
                """.formatted(username, username, email, username, password, password));
        return Long.parseLong(lastLine(output));
    }

    public void mintToken(String username, String tokenValue) {
        Rails.run("""
                user = User.find_by_username('%s')
                user.personal_access_tokens.where(name: 'e2e').delete_all
                token = user.personal_access_tokens.create!(
                  scopes: ['api'], name: 'e2e', expires_at: 1.day.from_now)
                token.set_token('%s')
                token.save!
                """.formatted(username, tokenValue));
    }

    // --- instance settings -----------------------------------------------------------------------

    /**
     * GitLab refuses webhook deliveries to private networks by default — the mirror image of our own
     * SSRF guard. The caller must ASSERT the setting took, not assume this call succeeded: a
     * silently-unapplied setting presents as the bot going quiet, which is indistinguishable from a
     * legitimate policy decline because nothing reaches our side to log.
     */
    public void allowLocalWebhooks() {
        put("/application/settings?allow_local_requests_from_web_hooks_and_services=true", null);
    }

    // --- projects and commits --------------------------------------------------------------------

    public long createProject(String name) {
        return post("/projects", Map.of(
                "name", name,
                "path", name,
                "visibility", "private",
                "initialize_with_readme", false)).get("id").asLong();
    }

    /** access_level 40 is Maintainer: enough to push, comment, resolve and merge. */
    public void addMember(long projectId, long userId) {
        post("/projects/" + projectId + "/members", Map.of("user_id", userId, "access_level", 40));
    }

    public record FileAction(String action, String filePath, String content, String previousPath) {

        public static FileAction create(String path, String content) {
            return new FileAction("create", path, content, null);
        }

        public static FileAction update(String path, String content) {
            return new FileAction("update", path, content, null);
        }

        public static FileAction delete(String path) {
            return new FileAction("delete", path, null, null);
        }

        /**
         * A real move, in one commit.
         *
         * <p>Expressed as delete-plus-create it would be a 0%-similarity change, and the rename
         * scenario is specifically about a 100%-similarity rename — different inputs answering
         * different questions, with the wrong one quietly reporting the wrong answer.
         */
        public static FileAction move(String newPath, String previousPath, String content) {
            return new FileAction("move", newPath, content, previousPath);
        }
    }

    /** @param startBranch branch to fork from, or null to commit onto an existing branch. */
    public String commit(long projectId, String branch, String startBranch, String message,
                         List<FileAction> actions) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (FileAction action : actions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("action", action.action());
            entry.put("file_path", action.filePath());
            if (action.previousPath() != null) {
                entry.put("previous_path", action.previousPath());
            }
            if (action.content() != null) {
                entry.put("content", action.content());
            }
            payload.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch", branch);
        if (startBranch != null) {
            body.put("start_branch", startBranch);
        }
        body.put("commit_message", message);
        body.put("actions", payload);

        return post("/projects/" + projectId + "/repository/commits", body).get("id").asText();
    }

    public long openMergeRequest(long projectId, String sourceBranch, String targetBranch, String title) {
        return post("/projects/" + projectId + "/merge_requests", Map.of(
                "source_branch", sourceBranch,
                "target_branch", targetBranch,
                "title", title)).get("iid").asLong();
    }

    public void mergeMergeRequest(long projectId, long iid) {
        put("/projects/" + projectId + "/merge_requests/" + iid + "/merge", Map.of());
    }

    // --- conversation ----------------------------------------------------------------------------

    public JsonNode mergeRequestNotes(long projectId, long iid) {
        return get("/projects/" + projectId + "/merge_requests/" + iid + "/notes?per_page=100");
    }

    public JsonNode discussions(long projectId, long iid) {
        return get("/projects/" + projectId + "/merge_requests/" + iid + "/discussions?per_page=100");
    }

    public void addNote(long projectId, long iid, String body) {
        post("/projects/" + projectId + "/merge_requests/" + iid + "/notes", Map.of("body", body));
    }

    public void replyToDiscussion(long projectId, long iid, String discussionId, String body) {
        post("/projects/" + projectId + "/merge_requests/" + iid + "/discussions/" + discussionId
                + "/notes", Map.of("body", body));
    }

    /**
     * Opens a new discussion anchored to a NEW-side line. The position needs the merge request's own
     * diff_refs, which is why they are fetched here rather than threaded through every caller.
     */
    public void createDiscussionOnLine(long projectId, long iid, String path, int newLine, String body) {
        JsonNode refs = get("/projects/" + projectId + "/merge_requests/" + iid).get("diff_refs");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("position", Map.of(
                "base_sha", refs.get("base_sha").asText(),
                "start_sha", refs.get("start_sha").asText(),
                "head_sha", refs.get("head_sha").asText(),
                "position_type", "text",
                "new_path", path,
                "new_line", newLine));
        post("/projects/" + projectId + "/merge_requests/" + iid + "/discussions", payload);
    }

    // --- webhooks and cleanup --------------------------------------------------------------------

    public void createWebhook(long projectId, String url, String secretToken) {
        post("/projects/" + projectId + "/hooks", Map.of(
                "url", url,
                "token", secretToken,
                // BOTH, or the conversation scenarios receive nothing: merge_requests_events carries
                // open/update/merge, note_events carries every comment.
                "merge_requests_events", true,
                "note_events", true,
                "push_events", false,
                "enable_ssl_verification", false));
    }

    /**
     * Deletes by PREFIX rather than by id, so a run also cleans up after runs that crashed before
     * their own cleanup. A long-lived local stack would otherwise accumulate one project per run.
     */
    public void deleteProjectsNamed(String prefix) {
        JsonNode projects = get("/projects?owned=true&per_page=100&search="
                + URLEncoder.encode(prefix, StandardCharsets.UTF_8));
        for (JsonNode project : projects) {
            if (project.get("path").asText().startsWith(prefix)) {
                delete("/projects/" + project.get("id").asLong());
            }
        }
    }

    // --- transport -------------------------------------------------------------------------------

    public JsonNode get(String path) {
        return send(request(path).GET());
    }

    private JsonNode post(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    private JsonNode put(String path, Object body) {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body));
        return send(request(path).header("Content-Type", "application/json").PUT(payload));
    }

    private void delete(String path) {
        send(request(path).DELETE());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(Stack.gitlabBaseUrl() + "/api/v4" + path))
                .timeout(Duration.ofSeconds(60))
                .header("PRIVATE-TOKEN", token);
    }

    private JsonNode send(HttpRequest.Builder builder) {
        HttpRequest built = builder.build();
        try {
            HttpResponse<String> response =
                    Stack.http().send(built, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("GitLab " + response.statusCode() + " for "
                        + built.method() + " " + built.uri() + ": " + response.body());
            }
            return response.body().isBlank() ? Json.read("{}") : Json.read(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("GitLab request failed: " + built.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling GitLab", e);
        }
    }

    private static String lastLine(String output) {
        String[] lines = output.strip().split("\\R");
        return lines[lines.length - 1].strip();
    }
}
