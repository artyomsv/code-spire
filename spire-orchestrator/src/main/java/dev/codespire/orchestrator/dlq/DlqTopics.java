package dev.codespire.orchestrator.dlq;

import java.util.Set;

/**
 * Maps a dead-lettered record's {@code type} discriminator to the cs.* topic it was
 * originally published on, so replay re-publishes to the right place. Deterministic and
 * unit-testable — deliberately NOT derived from SmallRye's own DLQ headers (CONTRACT §9).
 */
final class DlqTopics {

    static final String COMMANDS = "cs.commands";
    static final String RESULTS = "cs.results";
    static final String INTEGRATION = "cs.integration";

    private static final Set<String> ACTION_COMMAND_TYPES = Set.of(
            "FetchDiff", "GatherContext", "GenerateReview", "PostComments", "AnswerFollowUp");

    private static final Set<String> RESULT_EVENT_TYPES = Set.of(
            "DiffFetched", "ContextAssembled", "ReviewGenerated", "CommentsPosted",
            "ReviewFailed", "FollowUpGenerated", "FollowUpPosted");

    private static final Set<String> INGRESS_EVENT_TYPES = Set.of(
            "PullRequestEventReceived", "PullRequestClosed", "ManualCommandReceived",
            "AuthorReplied", "PushReceived");

    /**
     * The factory's two topics. Before these sets existed a run record fell through to
     * {@code cs.commands}, where the review worker's deserializer cannot read it: the run was never
     * recovered, and a record carrying credentials was copied onto a second topic.
     */
    static final String RUN_COMMANDS = "cs.run-commands";
    static final String RUN_RESULTS = "cs.run-results";

    private static final Set<String> RUN_COMMAND_TYPES = Set.of("ExecuteRun", "CancelRun");

    private static final Set<String> RUN_RESULT_TYPES = Set.of("RunStarted", "RunFinished", "RunFailed");

    private DlqTopics() {
    }

    /** Unknown/blank types fall back to cs.commands — the biggest DLQ source is AnswerFollowUp. */
    static String forType(String type) {
        if (ACTION_COMMAND_TYPES.contains(type)) {
            return COMMANDS;
        }
        if (RESULT_EVENT_TYPES.contains(type)) {
            return RESULTS;
        }
        if (INGRESS_EVENT_TYPES.contains(type)) {
            return INTEGRATION;
        }
        if (RUN_COMMAND_TYPES.contains(type)) {
            return RUN_COMMANDS;
        }
        if (RUN_RESULT_TYPES.contains(type)) {
            return RUN_RESULTS;
        }
        return COMMANDS;
    }
}
