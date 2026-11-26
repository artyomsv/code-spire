package dev.codespire.llm;

/**
 * How much diff a REVIEW or RECONCILE call sends, measured against today's fixed budget for its kind (review input
 * limit design, 2026-11-26). It decides whether a timed-out call may be retried: each attempt is non-streaming and
 * sends the whole prompt again, and may be billed.
 */
public enum PromptSize {

    /** At most today's fixed diff budget: a timeout is retried as it always was. */
    WITHIN_DEFAULT,

    /** More diff than today's fixed budget: a timeout fails the review rather than re-sending the prompt. */
    LARGE
}
