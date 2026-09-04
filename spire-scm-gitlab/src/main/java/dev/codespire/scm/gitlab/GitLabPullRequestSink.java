package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Opens a merge request on GitLab (SCM-MAPPING.md §8).
 *
 * <p>Two of §8's four divergences live here, and both are silent when wrong.
 *
 * <p><b>GitLab numbers a merge request twice.</b> {@code iid} is the per-project number in the URL
 * and in every API path; {@code id} is a global identifier that addresses nothing a human can see.
 * Reading {@code id} yields a number that looks entirely valid, stores cleanly in
 * {@code factory_run.pr_id}, and points at some other project's merge request. There is no failure —
 * only a link that goes somewhere else.
 *
 * <p><b>GitLab does not refuse a duplicate.</b> Unlike GitHub, a second create from the same source
 * branch succeeds and opens a second merge request. So the find-first call is not belt-and-braces
 * here; it is the only thing standing between a redelivered record and two merge requests.
 */
public class GitLabPullRequestSink implements PullRequestSink {

    /** GitLab's wording when the source branch has nothing the target lacks. */
    private static final String NO_CHANGES = "no changes";

    /** And its wording for a duplicate, which succeeds anyway on this forge — see the class javadoc. */
    private static final String ALREADY_EXISTS = "already exists";

    private final GitLabClient client;

    public GitLabPullRequestSink(GitLabClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITLAB;
    }

    @Override
    public PullRequestRef open(RepoRef repo, NewPullRequest request) {
        Optional<PullRequestRef> existing = findByHead(repo, request.headBranch());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return read(client.postJson(mergeRequestsPath(repo), Map.of(
                    "source_branch", request.headBranch(),
                    "target_branch", request.baseBranch(),
                    "title", request.title(),
                    "description", request.bodyMd())), mergeRequestsPath(repo));
        } catch (GitLabApiException e) {
            return recover(repo, request, e);
        }
    }

    @Override
    public Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch) {
        if (headBranch == null || headBranch.isBlank()) {
            throw new IllegalArgumentException("a source branch is needed to look for its merge request");
        }
        // "opened", not "open" -- GitLab spells this state differently from every other forge, and a
        // wrong value is not an error here: the API ignores an unknown state filter and returns
        // everything, so a merged merge request would suppress a new one and nothing would say why.
        String path = mergeRequestsPath(repo) + "?state=opened&per_page=1&source_branch="
                + URLEncoder.encode(headBranch, StandardCharsets.UTF_8);
        JsonNode found = client.getJson(path);
        if (!found.isArray() || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(found.get(0), path));
    }

    private PullRequestRef recover(RepoRef repo, NewPullRequest request, GitLabApiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (detail.contains(NO_CHANGES)) {
            throw new NothingToPropose("the branch " + request.headBranch() + " has no changes "
                    + request.baseBranch() + " does not already have, so there is nothing to review", e);
        }
        if (detail.contains(ALREADY_EXISTS)) {
            return findByHead(repo, request.headBranch()).orElseThrow(() -> e);
        }
        throw e;
    }

    /**
     * <b>{@code iid}, never {@code id}.</b> See the class javadoc: the wrong one is a valid-looking
     * number pointing at another project.
     */
    private static PullRequestRef read(JsonNode node, String path) {
        long number = node.path("iid").asLong(0);
        String url = node.path("web_url").asText("");
        if (number <= 0 || url.isBlank()) {
            throw new GitLabApiException(200, "POST", path,
                    "response carried no merge request iid or web_url");
        }
        return new PullRequestRef(number, url);
    }

    /** GitLab addresses a project by URL-encoded {@code namespace/path}, nesting included. */
    private static String mergeRequestsPath(RepoRef repo) {
        return "/projects/" + URLEncoder.encode(repo.full(), StandardCharsets.UTF_8) + "/merge_requests";
    }
}
