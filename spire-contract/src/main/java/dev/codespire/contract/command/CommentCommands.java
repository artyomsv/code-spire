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

    private CommentCommands() {
    }
}
