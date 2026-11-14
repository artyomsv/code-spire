package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.PullRequestComment;
import dev.codespire.contract.scm.RepoRef;

import java.util.ArrayList;
import java.util.List;

/**
 * A merge request's notes with their authors' stable ids, for context links from trusted people (step 8b). The notes
 * list holds both general and diff-discussion notes; system notes (state changes) are left out, as is a note with no
 * body. Walked 100 per page until a short page, at most {@link #MAX_PAGES} pages, because a fork author decides how
 * many notes there are.
 */
final class GitLabMergeRequestComments {

    static final int MAX_PAGES = 10;
    private static final int PAGE_SIZE = 100;

    private final GitLabClient client;

    GitLabMergeRequestComments(GitLabClient client) {
        this.client = client;
    }

    List<PullRequestComment> read(RepoRef repo, long prId) {
        String path = GitLabDiffSource.mrPath(repo, prId) + "/notes?per_page=" + PAGE_SIZE + "&page=";
        List<PullRequestComment> comments = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode notes = client.getJson(path + page);
            for (JsonNode note : notes) {
                String body = note.path("body").asText("");
                if (!note.path("system").asBoolean(false) && !body.isBlank()) {
                    comments.add(new PullRequestComment(author(note.path("author")), body));
                }
            }
            if (notes.size() < PAGE_SIZE) {
                break;
            }
        }
        return comments;
    }

    private static Author author(JsonNode user) {
        String username = user.path("username").asText("");
        return Author.of(user.path("id").asText(""), username, user.path("name").asText(username));
    }
}
