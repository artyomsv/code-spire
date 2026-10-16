package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ReviewSubmission.InlineComment;
import dev.codespire.contract.scm.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The pull request review calls (ADR-047 §5.2): one POST carries verdict, body and inline comments.
 *
 * <p>Package-private: {@link GitHubCommentSink} is the port implementation and delegates here, so the
 * review calls do not grow that class past what a reader can hold.
 */
final class GitHubReviewApi {

    private static final int UNPROCESSABLE = 422;
    /** GitHub's wording for a verdict on one's own pull request, lower-cased at the comparison. */
    private static final String OWN_PULL_REQUEST = "own pull request";
    private static final int PAGE = 100;
    private static final int MAX_PAGES = 20;
    /** The states that carry a reviewer's standing on a pull request; COMMENTED replaces nothing. */
    private static final Set<String> STATE_REVIEWS = Set.of("APPROVED", "CHANGES_REQUESTED", "DISMISSED");

    private final GitHubClient client;

    GitHubReviewApi(GitHubClient client) {
        this.client = client;
    }

    PostedReview post(RepoRef repo, long prId, String headCommit, ReviewSubmission review) {
        Map<String, Object> body = new HashMap<>();
        body.put("commit_id", headCommit);
        body.put("event", review.verdict().name());
        body.put("body", review.bodyMd());
        List<Map<String, Object>> comments = new ArrayList<>();
        for (InlineComment comment : review.comments()) {
            comments.add(comment(comment));
        }
        body.put("comments", comments);
        String path = reviewsPath(repo, prId);
        JsonNode created;
        try {
            created = client.postJson(path, body);
        } catch (GitHubApiException e) {
            throw classify(e, review);
        }
        String id = created.path("id").asText("");
        if (id.isBlank()) {
            throw new GitHubApiException(200, "POST", path, "2xx response carried no review id");
        }
        return new PostedReview(id, review.verdict(), readBack(repo, prId, id, review.comments()),
                created.path("html_url").asText(null));
    }

    /** The per-comment mapping {@code GitHubCommentSink.postInline} uses, without {@code commit_id} (top-level). */
    static Map<String, Object> comment(InlineComment comment) {
        InlineAnchor anchor = comment.anchor();
        boolean old = anchor.side() == Side.OLD;
        Map<String, Object> body = new HashMap<>();
        body.put("body", comment.bodyMd());
        body.put("path", anchor.path());
        body.put("side", old ? "LEFT" : "RIGHT");
        if (!old && anchor.endNewLine() != null && anchor.endNewLine() > anchor.newLine()) {
            body.put("start_line", anchor.newLine());
            body.put("start_side", "RIGHT");
            body.put("line", anchor.endNewLine());
        } else {
            body.put("line", old ? anchor.oldLine() : anchor.newLine());
        }
        return body;
    }

    /**
     * Which failure a 422 is (spec §5.2). The anchor error wording is not stable across the API's history,
     * so the rule is by shape: the self-authored wording, else any 422 on a review that carried comments.
     */
    private static RuntimeException classify(GitHubApiException e, ReviewSubmission review) {
        if (e.status() != UNPROCESSABLE) {
            return e;
        }
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (detail.contains(OWN_PULL_REQUEST)) {
            return new CommentSink.ReviewVerdictRefused(e.getMessage(), e);
        }
        if (!review.comments().isEmpty()) {
            return new CommentSink.ReviewAnchorRejected(e.getMessage(), e);
        }
        return e;
    }

    /**
     * Each submitted anchor's comment id, keyed by {@link InlineAnchor#anchorKey()}, read back from the
     * pull request's comment list filtered to this review. Not from {@code /reviews/{id}/comments}: live
     * on 2026-10-16 that list answered in the legacy shape — {@code path} and {@code position} only,
     * {@code line}/{@code side}/{@code start_line} null — so nothing matched and a comment that was on
     * GitHub was reported as not posted. The pull request's list carries {@code line}, {@code side} and
     * {@code pull_request_review_id}.
     */
    Map<String, String> readBack(RepoRef repo, long prId, String reviewId, List<InlineComment> comments) {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (comments.isEmpty()) {
            return byKey;
        }
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode rows = client.getJson(commentsPath(repo, prId) + "?per_page=" + PAGE + "&page=" + page);
            for (JsonNode row : rows) {
                if (!reviewId.equals(row.path("pull_request_review_id").asText(""))) {
                    continue;
                }
                for (InlineComment comment : comments) {
                    if (matches(row, comment.anchor())) {
                        byKey.putIfAbsent(comment.anchor().anchorKey(), row.path("id").asText());
                    }
                }
            }
            if (rows.size() < PAGE) {
                break;
            }
        }
        return byKey;
    }

    /**
     * GitHub's {@code line} is the END of a range and {@code start_line} its start; the anchor key is the
     * START line ({@link InlineAnchor#anchorKey()}). So: same path, same side, and {@code start_line}
     * when present else {@code line} equals the anchor's line.
     */
    static boolean matches(JsonNode row, InlineAnchor anchor) {
        boolean old = anchor.side() == Side.OLD;
        Integer line = old ? anchor.oldLine() : anchor.newLine();
        // GitHub nulls line/start_line once the head moves past the comment and keeps the original
        // pair; the anchor was resolved against the reviewed head, so the original is the match.
        int start = row.hasNonNull("start_line") ? row.path("start_line").asInt()
                : row.hasNonNull("original_start_line") ? row.path("original_start_line").asInt()
                : row.hasNonNull("line") ? row.path("line").asInt()
                : row.path("original_line").asInt(-1);
        return line != null && line == start
                && anchor.path().equals(row.path("path").asText(""))
                && (old ? "LEFT" : "RIGHT").equals(row.path("side").asText(""));
    }

    /** The bot's latest review of {@code headCommit} whose body starts with the submission's first line. */
    Optional<PostedReview> find(RepoRef repo, long prId, String headCommit, ReviewSubmission review, String botLogin) {
        if (botLogin == null || botLogin.isBlank()) {
            return Optional.empty();
        }
        String prefix = review.firstLine();
        JsonNode found = null;
        for (JsonNode row : walkReviews(repo, prId)) {
            if (botLogin.equals(row.path("user").path("login").asText(""))
                    && headCommit.equals(row.path("commit_id").asText(""))
                    && row.path("body").asText("").startsWith(prefix)) {
                found = row;
            }
        }
        if (found == null) {
            return Optional.empty();
        }
        String id = found.path("id").asText();
        return Optional.of(new PostedReview(id, verdictOf(found.path("state").asText("")),
                readBack(repo, prId, id, review.comments()), found.path("html_url").asText(null)));
    }

    static ReviewVerdict verdictOf(String state) {
        return switch (state) {
            case "APPROVED" -> ReviewVerdict.APPROVE;
            case "CHANGES_REQUESTED" -> ReviewVerdict.REQUEST_CHANGES;
            default -> ReviewVerdict.COMMENT;
        };
    }

    void updateBody(RepoRef repo, long prId, String reviewId, String bodyMd) {
        client.putJson(reviewsPath(repo, prId) + "/" + reviewId, Map.of("body", bodyMd));
    }

    /**
     * D11. A reviewer's state on a pull request is its latest APPROVED / CHANGES_REQUESTED / DISMISSED
     * review (a COMMENTED one replaces nothing): when that is CHANGES_REQUESTED, dismiss it.
     */
    Optional<String> dismissChangesRequested(RepoRef repo, long prId, String botLogin, String message) {
        if (botLogin == null || botLogin.isBlank()) {
            return Optional.empty();
        }
        JsonNode latest = null;
        for (JsonNode row : walkReviews(repo, prId)) {
            if (botLogin.equals(row.path("user").path("login").asText(""))
                    && STATE_REVIEWS.contains(row.path("state").asText(""))) {
                latest = row;
            }
        }
        if (latest == null || !"CHANGES_REQUESTED".equals(latest.path("state").asText(""))) {
            return Optional.empty();
        }
        String id = latest.path("id").asText();
        client.putJson(reviewsPath(repo, prId) + "/" + id + "/dismissals", Map.of("message", message));
        return Optional.of(id);
    }

    private List<JsonNode> walkReviews(RepoRef repo, long prId) {
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode pageRows = client.getJson(reviewsPath(repo, prId) + "?per_page=" + PAGE + "&page=" + page);
            pageRows.forEach(rows::add);
            if (pageRows.size() < PAGE) {
                break;
            }
        }
        return rows;
    }

    private static String reviewsPath(RepoRef repo, long prId) {
        return "/repos/" + repo.full() + "/pulls/" + prId + "/reviews";
    }

    private static String commentsPath(RepoRef repo, long prId) {
        return "/repos/" + repo.full() + "/pulls/" + prId + "/comments";
    }
}
