package dev.codespire.worker.pipeline;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code comment_idempotency} in memory, following its SQL: a row's {@code posted_ref} is null while open. {@link #open}
 * and {@link #supersede} are the orchestrator's statements (SkippedRunReceipts): insert-if-absent, and close every open
 * {@code CI_FAILED:} row. {@link #take} and {@link #markTaken} are the worker's compare-and-set updates.
 */
class ReceiptStore extends CommentIdempotencyStore {

    private static final String OPEN = "\u0000open";
    private final Map<String, String> rows = new HashMap<>();

    void open(String reviewId, String commit, String anchorKey) {
        rows.putIfAbsent(key(reviewId, commit, anchorKey), OPEN);
    }

    void supersede(String reviewId) {
        rows.replaceAll((key, ref) -> key.startsWith(reviewId + "/") && key.contains("/" + ChecksFailedLabel.CLAIM_PREFIX)
                && OPEN.equals(ref) ? "superseded" : ref);
    }

    String refOf(String reviewId, String commit, String anchorKey) {
        String ref = rows.get(key(reviewId, commit, anchorKey));
        return OPEN.equals(ref) ? null : ref;
    }

    @Override
    public Claim claim(String reviewId, String commit, String anchorKey) {
        String ref = refOf(reviewId, commit, anchorKey);
        if (ref != null) return new Claim.AlreadyPosted(ref);
        rows.put(key(reviewId, commit, anchorKey), OPEN);
        return new Claim.Post();
    }

    @Override
    public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
        rows.put(key(reviewId, commit, anchorKey), postedRef);
    }

    @Override
    public boolean take(String reviewId, String commit, String anchorKey) {
        return rows.replace(key(reviewId, commit, anchorKey), OPEN, TAKEN);
    }

    @Override
    public void markTaken(String reviewId, String commit, String anchorKey, String outcome) {
        rows.replace(key(reviewId, commit, anchorKey), TAKEN, outcome);
    }

    @Override
    public Map<String, String> postedFor(String reviewId, String commit) {
        Map<String, String> posted = new LinkedHashMap<>();
        String prefix = reviewId + "/" + commit + "/";
        rows.forEach((key, ref) -> {
            if (key.startsWith(prefix) && !OPEN.equals(ref)) posted.put(key.substring(prefix.length()), ref);
        });
        return posted;
    }

    private static String key(String reviewId, String commit, String anchorKey) {
        return reviewId + "/" + commit + "/" + anchorKey;
    }
}
