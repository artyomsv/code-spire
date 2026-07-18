package dev.codespire.contract.review;

/**
 * One finding from the last POSTED run, carried into a follow-up review
 * (command-carried prior state — ADR-019). {@code threadRef} is null when the
 * prior inline post failed: the finding still feeds the exclusion list but no
 * thread actions apply.
 */
public record PriorFinding(String path, int line, Severity severity, String message, String threadRef) {
}
