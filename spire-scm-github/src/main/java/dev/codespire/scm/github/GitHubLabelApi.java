package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.scm.RepoRef;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The verdict label calls (verdict labels, V120): a pull request's labels are its issue's labels on GitHub.
 *
 * <p>Package-private: {@link GitHubCommentSink} is the port implementation and delegates here. One read, then only
 * the calls that change something, so a replay of the same verdict writes nothing.
 */
final class GitHubLabelApi {

    private static final int NOT_FOUND = 404;
    private static final int UNPROCESSABLE = 422;
    /** A pull request carries a handful of labels; one full page is far past any real one. */
    private static final int PAGE = 100;
    private static final String APPROVED_COLOR = "0e8a16";
    private static final String CHANGES_COLOR = "d93f0b";
    private static final String OTHER_COLOR = "ededed";

    private final GitHubClient client;

    GitHubLabelApi(GitHubClient client) {
        this.client = client;
    }

    LabelOutcome set(RepoRef repo, long prId, String label, String prefix) {
        List<String> current = current(repo, prId);
        boolean changed = false;
        for (String name : current) {
            if (name.startsWith(prefix) && !name.equals(label)) {
                remove(repo, prId, name);
                changed = true;
            }
        }
        if (!current.contains(label)) {
            add(repo, prId, label);
            changed = true;
        }
        return changed ? LabelOutcome.APPLIED : LabelOutcome.ALREADY_SET;
    }

    private List<String> current(RepoRef repo, long prId) {
        JsonNode labels = client.getJson(labelsPath(repo, prId) + "?per_page=" + PAGE);
        List<String> names = new ArrayList<>();
        for (JsonNode node : labels) {
            String name = node.path("name").asText("");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** A label someone removed between the read and this call is already gone, which is what was asked. */
    private void remove(RepoRef repo, long prId, String name) {
        try {
            client.delete(labelsPath(repo, prId) + "/" + encode(name));
        } catch (GitHubApiException e) {
            if (e.status() != NOT_FOUND) {
                throw e;
            }
        }
    }

    /**
     * GitHub refuses to add a label the repository does not define (422, or 404 on some versions): create it, then
     * add it once more. A second refusal is the caller's failure to report.
     */
    private void add(RepoRef repo, long prId, String label) {
        try {
            client.postJson(labelsPath(repo, prId), Map.of("labels", List.of(label)));
        } catch (GitHubApiException e) {
            if (e.status() != UNPROCESSABLE && e.status() != NOT_FOUND) {
                throw e;
            }
            create(repo, label);
            client.postJson(labelsPath(repo, prId), Map.of("labels", List.of(label)));
        }
    }

    /** A 422 here is "already_exists": another writer created it first, which is what was asked. */
    private void create(RepoRef repo, String label) {
        try {
            client.postJson("/repos/" + repo.full() + "/labels", Map.of("name", label, "color", colorOf(label)));
        } catch (GitHubApiException e) {
            if (e.status() != UNPROCESSABLE) {
                throw e;
            }
        }
    }

    private static String colorOf(String label) {
        if (label.endsWith("approved")) {
            return APPROVED_COLOR;
        }
        return label.endsWith("changes-requested") ? CHANGES_COLOR : OTHER_COLOR;
    }

    private static String labelsPath(RepoRef repo, long prId) {
        return "/repos/" + repo.full() + "/issues/" + prId + "/labels";
    }

    /** A path segment: {@code review: approved} is {@code review%3A%20approved}, never a {@code +} for the space. */
    static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
