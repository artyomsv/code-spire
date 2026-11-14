package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.PullRequestComment;
import dev.codespire.contract.scm.RepoRef;

import java.util.ArrayList;
import java.util.List;

/**
 * A pull request's comments with their authors' stable ids, for context links from trusted people (step 8b): the
 * conversation ({@code /issues/{n}/comments}) and the inline review comments ({@code /pulls/{n}/comments}). Each list
 * is walked 100 per page until a short page, at most {@link #MAX_PAGES} pages, because a fork author decides how many
 * comments there are. A comment with no body is left out.
 */
final class GitHubPullRequestComments {

    static final int MAX_PAGES = 5;
    private static final int PAGE_SIZE = 100;

    private final GitHubClient client;

    GitHubPullRequestComments(GitHubClient client) {
        this.client = client;
    }

    List<PullRequestComment> read(RepoRef repo, long prId) {
        String base = "/repos/" + repo.full();
        List<PullRequestComment> comments = new ArrayList<>();
        comments.addAll(walk(base + "/issues/" + prId + "/comments"));
        comments.addAll(walk(base + "/pulls/" + prId + "/comments"));
        return comments;
    }

    private List<PullRequestComment> walk(String path) {
        List<PullRequestComment> comments = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode rows = client.getJson(path + "?per_page=" + PAGE_SIZE + "&page=" + page);
            for (JsonNode row : rows) {
                String body = row.path("body").asText("");
                if (!body.isBlank()) {
                    comments.add(new PullRequestComment(author(row.path("user")), body));
                }
            }
            if (rows.size() < PAGE_SIZE) {
                break;
            }
        }
        return comments;
    }

    private static Author author(JsonNode user) {
        String login = user.path("login").asText("");
        return Author.of(user.path("id").asText(""), login, login);
    }
}
