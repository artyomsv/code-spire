package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.IdentitySource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * GitLab read adapter (SCM-MAPPING §2/§3). A merge request is addressed by its
 * repo-scoped {@code iid} ({@code prId}) under the URL-encoded project path
 * ({@code workspace}=top group, {@code slug}=the rest of the namespace + project,
 * which can be nested — {@code group/subgroup/project}). GitLab returns the diff
 * as per-file JSON with a header-less {@code diff} fragment, so each file is
 * wrapped in a synthesized {@code diff --git} header before the shared
 * {@link UnifiedDiffParser} runs. Anchoring an inline comment on this API needs a base/start/head
 * SHA triple rather than the head alone; the extra SHAs are read from the merge request by the
 * comment sink, so nothing provider-shaped crosses the SPI.
 */
public class GitLabDiffSource implements DiffSource, IdentitySource {

    private final GitLabClient client;

    public GitLabDiffSource(GitLabClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITLAB;
    }

    @Override
    public String apiHost() {
        return client.apiHost(); // gitlab.com or a self-managed host — keyed per instance
    }

    /** GET /user — the token owner (SCM-MAPPING §GitLab); {@code id} is the stable numeric account id. */
    @Override
    public Author whoami() {
        JsonNode user = client.getJson("/user");
        String username = user.path("username").asText("");
        return Author.of(user.path("id").asText(""), username, user.path("name").asText(username));
    }

    @Override
    public void assertRepoAccessible(RepoRef repo) {
        client.getJson("/projects/" + encodedProject(repo));
    }

    @Override
    public PullRequest fetchPullRequest(RepoRef repo, long prId) {
        JsonNode mr = client.getJson(mrPath(repo, prId));
        return new PullRequest(
                repo,
                prId,
                mr.path("title").asText(""),
                mr.path("description").asText(""),
                mr.path("source_branch").asText(""),
                mr.path("target_branch").asText(""),
                headSha(mr.path("diff_refs")),
                author(mr.path("author")),
                mr.path("web_url").asText(""));
    }

    @Override
    public Diff fetchDiff(RepoRef repo, long prId, String commit) {
        // /changes returns the file diffs AND diff_refs in one call, and is
        // available on every GitLab version (including self-managed) — the
        // three SHAs are required to post inline discussions.
        JsonNode changes = client.getJson(mrPath(repo, prId) + "/changes");
        List<FilePatch> files = UnifiedDiffParser.parse(synthesizeUnifiedDiff(changes.path("changes")));
        return new Diff(headSha(changes.path("diff_refs")), files,
                changes.path("overflow").asBoolean(false));
    }

    /**
     * The reconciliation lens (prior head -> new head). GitLab's compare endpoint
     * returns per-file {@code diffs[]} the same header-less shape as {@code /changes},
     * so each entry is wrapped with a minimal {@code ---}/{@code +++} pair rather than
     * the full {@code diff --git} header — enough for the shared parser's hunk reader.
     */
    @Override
    /**
     * Compare's {@code diffs[]} carries the same header-less fragments as the MR's {@code changes[]},
     * so it needs the same {@code diff --git} header re-attached — {@link #synthesizeUnifiedDiff} is
     * that logic. Emitting only {@code ---}/{@code +++} produced a string that LOOKS like a diff and
     * parses to ZERO files, because the shared parser keys on the {@code diff --git} line. Everything
     * reading this diff as text (the reconcile prompt) worked; everything parsing it silently saw an
     * empty change set — which downgraded every STILL_OPEN verdict to UNCHANGED on GitLab alone, so an
     * author who partly fixed a finding was never told what remained.
     */
    public String fetchCompareDiff(RepoRef repo, String base, String head) {
        String path = "/projects/" + encodedProject(repo) + "/repository/compare?from=" + base + "&to=" + head;
        return synthesizeUnifiedDiff(client.getJson(path).path("diffs"));
    }

    /**
     * GitLab's {@code changes[].diff} is a header-less unified-diff fragment plus
     * out-of-band path/flags fields. Re-attach a {@code diff --git} header (and
     * the add/delete/rename markers) so the shared parser recognises each file
     * and derives its change type.
     */
    private static String synthesizeUnifiedDiff(JsonNode changesArray) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode change : changesArray) {
            String oldPath = change.path("old_path").asText("");
            String newPath = change.path("new_path").asText(oldPath);
            sb.append("diff --git a/").append(oldPath).append(" b/").append(newPath).append('\n');
            if (change.path("new_file").asBoolean(false)) {
                sb.append("new file mode 100644\n");
            } else if (change.path("deleted_file").asBoolean(false)) {
                sb.append("deleted file mode 100644\n");
            } else if (change.path("renamed_file").asBoolean(false)) {
                sb.append("rename from ").append(oldPath).append("\nrename to ").append(newPath).append('\n');
            }
            String diff = change.path("diff").asText("");
            sb.append(diff);
            if (!diff.isEmpty() && !diff.endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** The reviewed head. The base/start SHAs this API also wants are the sink's to fetch. */
    private static String headSha(JsonNode refs) {
        return nullIfBlank(refs.path("head_sha").asText(""));
    }

    private static Author author(JsonNode user) {
        String username = user.path("username").asText("");
        return Author.of(user.path("id").asText(""), username, user.path("name").asText(username));
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** {@code /projects/{url-encoded namespace/project}/merge_requests/{iid}}. */
    static String mrPath(RepoRef repo, long prId) {
        return "/projects/" + encodedProject(repo) + "/merge_requests/" + prId;
    }

    /**
     * {@code GET /projects/{enc}/repository/files/{enc-path}/raw?ref={branch}}.
     *
     * <p>The file path is URL-encoded too — GitLab addresses a file the same way it addresses a
     * project, so {@code docs/rules.md} must arrive as {@code docs%2Frules.md} rather than as extra
     * path segments. A 404 means the repository has no such file, the ordinary case, so it yields
     * null; anything else propagates.
     */
    @Override
    public String fetchTextFileOnBranch(RepoRef repo, String branch, String path) {
        try {
            return client.getText("/projects/" + encodedProject(repo) + "/repository/files/"
                    + URLEncoder.encode(path, StandardCharsets.UTF_8)
                    + "/raw?ref=" + URLEncoder.encode(branch, StandardCharsets.UTF_8));
        } catch (GitLabApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
    }

    /** GitLab addresses a project by its URL-encoded {@code namespace/project} path. */
    private static String encodedProject(RepoRef repo) {
        return URLEncoder.encode(repo.full(), StandardCharsets.UTF_8);
    }
}
