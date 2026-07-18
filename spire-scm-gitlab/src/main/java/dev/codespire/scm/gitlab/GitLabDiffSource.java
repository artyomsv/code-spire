package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.IdentitySource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
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
 * {@link UnifiedDiffParser} runs. Unlike GitHub, GitLab needs all three
 * {@link DiffRefs} SHAs to anchor an inline comment, so they are read from the
 * merge request's {@code diff_refs}.
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

    /** GET /user — the token owner (SCM-MAPPING §GitLab); {@code id} is the stable numeric account id. */
    @Override
    public Author whoami() {
        JsonNode user = client.getJson("/user");
        String username = user.path("username").asText("");
        return Author.of(user.path("id").asText(""), username, user.path("name").asText(username));
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
                diffRefs(mr.path("diff_refs")),
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
        return new Diff(diffRefs(changes.path("diff_refs")), files, changes.path("overflow").asBoolean(false));
    }

    /**
     * The reconciliation lens (prior head -> new head). GitLab's compare endpoint
     * returns per-file {@code diffs[]} the same header-less shape as {@code /changes},
     * so each entry is wrapped with a minimal {@code ---}/{@code +++} pair rather than
     * the full {@code diff --git} header — enough for the shared parser's hunk reader.
     */
    @Override
    public String fetchCompareDiff(RepoRef repo, String base, String head) {
        String path = "/projects/" + encodedProject(repo) + "/repository/compare?from=" + base + "&to=" + head;
        JsonNode response = client.getJson(path);
        StringBuilder unified = new StringBuilder();
        for (JsonNode d : response.path("diffs")) {
            unified.append("--- a/").append(d.path("old_path").asText("")).append('\n')
                    .append("+++ b/").append(d.path("new_path").asText("")).append('\n')
                    .append(d.path("diff").asText(""));
        }
        return unified.toString();
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

    private static DiffRefs diffRefs(JsonNode refs) {
        return new DiffRefs(
                nullIfBlank(refs.path("base_sha").asText("")),
                nullIfBlank(refs.path("start_sha").asText("")),
                nullIfBlank(refs.path("head_sha").asText("")));
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

    /** GitLab addresses a project by its URL-encoded {@code namespace/project} path. */
    private static String encodedProject(RepoRef repo) {
        return URLEncoder.encode(repo.full(), StandardCharsets.UTF_8);
    }
}
