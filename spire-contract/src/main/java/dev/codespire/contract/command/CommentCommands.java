package dev.codespire.contract.command;

/**
 * The {@code /command} names carried on {@link dev.codespire.contract.event.IntegrationEvent
 * .ManualCommandReceived}. Part of the wire vocabulary, so both the service that translates a
 * comment into one and the service that acts on it read the same constant.
 *
 * <p>Anything else beginning with "/" is an ordinary comment, not a command.
 */
public final class CommentCommands {

    /** Force a re-review of the pull request's current head. */
    public static final String REVIEW = "review";

    /** File the surrounding thread's issue as a tracked finding. */
    public static final String FINDING = "finding";

    /**
     * Dispatch a factory run to fix the finding this thread belongs to (FR-F27).
     *
     * <p>The only command that spends on an AGENT rather than a review call, and the only one whose
     * output is a branch pushed to the repository. The finding it targets comes from the thread the
     * command was typed in, which is why the ingresses carry a thread ref on every command event and
     * not only on a reply.
     *
     * <p><b>{@code /fix} takes no arguments, and the text after it MUST NOT reach a model prompt.</b>
     * Written down here rather than left to the dispatch slice to decide, because by then a prompt
     * builder exists and the cheap moment has passed. The finding IS the specification (FR-F27); the
     * text after the command is typed by whoever can comment on the pull request, and feeding it to
     * an agent that holds a clone and a push token would let a commenter author instructions to it —
     * the widening ADR-036 forbids for repository-supplied text, arriving from a comment instead. If
     * a future slice wants author guidance, it goes in the untrusted-fenced slot a Jira ticket
     * already uses, never into the instruction part of the prompt.
     */
    public static final String FIX = "fix";

    private CommentCommands() {
    }
}
