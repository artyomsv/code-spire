package dev.codespire.contract.event;

/**
 * Why the worker chose not to answer a reply it was asked to answer ({@link IntegrationEvent.FollowUpDeclined}):
 * nothing was posted and no model was called. Without that event the orchestrator's "answering" flag, set when it sent
 * the ask, stayed up until the next review run (production PR #456: a third person replied without mentioning the
 * bot). It is deliberately not a {@code FollowUpPosted}: nothing was said, so no turn is counted. On the wire by name.
 */
public enum FollowUpDeclineReason {
    /** The forge's comment sink cannot read a thread, so replies are unsupported there. */
    NO_THREAD_SOURCE,
    /** A second person is in the thread and the reply did not mention the bot (scope "smart 1:1"). */
    MULTI_PARTY_NO_MENTION,
    /** This comment was answered already: a redelivery. */
    ALREADY_ANSWERED
}
