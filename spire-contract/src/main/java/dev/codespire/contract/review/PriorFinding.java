package dev.codespire.contract.review;

/**
 * One finding from the last POSTED run, carried into a follow-up review
 * (command-carried prior state — ADR-019). {@code threadRef} is null when the
 * prior inline post failed: the finding still feeds the exclusion list but no
 * thread actions apply.
 *
 * <p>{@code origin} mirrors {@code ReviewDetail.FindingView.origin()} — null for a finding the
 * review produced from the diff, {@code "conversation"} for one a human filed with {@code /finding}
 * — copied straight from the posted snapshot at construction ({@code ReviewProjection.toPriorFinding}).
 * Carrying it here, rather than re-deriving it later from loc/threadRef membership against some other
 * snapshot, is what lets a carried finding keep its provenance even when its own loc or threadRef is
 * remapped (a rename, or a newer thread superseding it in the {@code review_thread} index) — both of
 * which used to defeat a membership test that started from the finding's ORIGINAL anchor.
 */
public record PriorFinding(String path, int line, Severity severity, String message, String threadRef,
                           String origin) {

    /** A review-derived prior finding: the common case, and every call site untouched by
     *  conversation findings. */
    public PriorFinding(String path, int line, Severity severity, String message, String threadRef) {
        this(path, line, severity, message, threadRef, null);
    }
}
