package dev.codespire.e2e.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed reads over the orchestrator's read model.
 *
 * <p>Half of every assertion in this suite. The other half asks GitLab, because this side says what we
 * BELIEVE happened: a resolve that degraded to reply-only writes {@code ThreadReplied} rather than
 * {@code ThreadResolved} here, and only GitLab can say whether the thread is actually resolved.
 * Asserting one without the other is how a fake {@code resolved:true} survived in the GitHub adapter.
 *
 * <p><b>Findings and verdicts are deliberately NOT read here.</b> {@code posted_findings_json} and
 * {@code reconciliation_json} are Tink-encrypted with the review id as AAD (ADR-011), so psql sees
 * ciphertext. They come from {@code GET /api/reviews/...} instead, which is both the only way to read
 * them and the more honest one: it is what an operator sees.
 */
public final class ReadModel {

    /** A row of {@code review_thread}. Column set as of V27 (path/line/is_summary/resolved/root_ref/seq). */
    public record Thread(String threadRef, String path, String line, boolean isOurs, boolean isSummary,
                         boolean resolved, int turnCount, String rootRef) {
    }

    private ReadModel() {
    }

    public static String status(String reviewId) {
        return Psql.one("SELECT status FROM orchestrator.review_status WHERE review_id = "
                + quote(reviewId));
    }

    public static String prState(String reviewId) {
        return Psql.one("SELECT pr_state FROM orchestrator.review_status WHERE review_id = "
                + quote(reviewId));
    }

    public static boolean degraded(String reviewId) {
        return "t".equals(Psql.one("SELECT degraded FROM orchestrator.review_status WHERE review_id = "
                + quote(reviewId)));
    }

    public static long findingsCount(String reviewId) {
        return Long.parseLong(Psql.one(
                "SELECT findings_count FROM orchestrator.review_status WHERE review_id = "
                        + quote(reviewId)));
    }

    /** Counts one timeline event type. The timeline is plaintext; only payloads are encrypted. */
    public static long events(String reviewId, String type) {
        return Long.parseLong(Psql.one("SELECT count(*) FROM orchestrator.review_event WHERE review_id = "
                + quote(reviewId) + " AND type = " + quote(type)));
    }

    public static List<Thread> threads(String reviewId) {
        List<Thread> threads = new ArrayList<>();
        for (List<String> row : Psql.rows(
                "SELECT thread_ref, coalesce(path, ''), coalesce(line::text, ''), is_ours, is_summary, "
                        + "resolved, turn_count, coalesce(root_ref, '') "
                        + "FROM orchestrator.review_thread WHERE review_id = " + quote(reviewId)
                        + " ORDER BY seq")) {
            threads.add(new Thread(row.get(0), row.get(1), row.get(2), "t".equals(row.get(3)),
                    "t".equals(row.get(4)), "t".equals(row.get(5)),
                    Integer.parseInt(row.get(6)), row.get(7)));
        }
        return threads;
    }

    /** psql's -tA prints a literal backslash-free string, so only the quote needs doubling. */
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
