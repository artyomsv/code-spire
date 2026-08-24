package dev.codespire.contract.command;

/**
 * The wording and idempotency coordinates of a {@code /finding} refusal. Shared because the
 * ORCHESTRATOR decides to refuse and records the reason on the timeline, while the WORKER posts
 * these same words into the thread when there is one to post into — two literals in two services
 * would drift into a reply that no longer matches the reason logged for it, exactly the failure
 * {@link ArchivedNotice} exists to prevent.
 *
 * <p>The claim slot is the THREAD, not the triggering comment: unlike a finding confirmation, whose
 * text names a different severity and anchor every time, this text never changes. A second misuse in
 * the same thread has not been helped by hearing it again, so it finds the slot already taken instead
 * of collecting a second identical reply.
 */
public final class ConversationFindingRefusal {

    public static final String KEY = "finding-refusal";

    public static final String NO_ANCHOR_REPLY =
            "`/finding` needs to be on a specific line. Open an inline comment on the line in "
            + "question and run it there.";

    private ConversationFindingRefusal() {
    }
}
