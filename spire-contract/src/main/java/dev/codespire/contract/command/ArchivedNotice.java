package dev.codespire.contract.command;

/**
 * The idempotency coordinates of the archived notice. Shared because the worker TAKES this claim and
 * the orchestrator RELEASES it on unarchive; two literals in two services would drift into a notice
 * that never re-arms, with nothing failing.
 *
 * <p>The slot is a constant rather than a thread ref, which is what makes the notice fire once per
 * REVIEW instead of once per thread.
 */
public final class ArchivedNotice {

    public static final String SLOT = "archived-notice";
    public static final String KEY = "archived";

    private ArchivedNotice() {
    }
}
