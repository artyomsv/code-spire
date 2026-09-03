package dev.codespire.contract.event;

import java.time.Instant;
import java.util.Objects;

/**
 * One event from a run's live stream, on its way to the transcript (FR-F5).
 *
 * <p>The second of the two event tiers ADR-034 defines. The run stream — reasoning, tool calls, tool
 * results, output, state — rides {@code cs.run-events} into a bounded, TTL'd table. It exists for the
 * live tail, for debugging, and for a transcript an operator can read. <b>It is not replayable and
 * nothing derives state from it.</b> One observed agent run emitted 858 events where a review
 * produces a handful, so putting this into the aggregate's durable log would multiply event-store
 * volume by three orders of magnitude and make replay useless.
 *
 * <p><b>Deliberately not the harness's {@code RunEvent} sealed hierarchy.</b> ADR-034 keeps that
 * vocabulary in {@code spire-harness} precisely so it is not in the contract module, where its
 * presence would imply a durability guarantee this tier does not have and would invite a later
 * change to persist it "since it is already in the contract". What crosses the wire is this flat
 * envelope, with the kind as a string.
 *
 * <p><b>{@link #sequence} orders the transcript, not {@link #at}.</b> Two events from one agent can
 * share a millisecond, and the timestamp is stamped inside the sandbox — a clock this service does
 * not control and would be wrong to sort by.
 */
public record RunEventRecord(String runId, long sequence, Instant at, String kind, String text,
                             boolean error) {

    /**
     * The longest text one event may carry.
     *
     * <p>The agent writes to the same stream the harness does, at full access, so the volume is
     * influenced by whatever the model produced. The reference adapter already clips each field, but
     * that is one adapter's courtesy rather than a property of the wire, and a second arm inherits
     * none of it.
     */
    public static final int MAX_TEXT_CHARS = 8192;

    public static final String CLIPPED = "… [clipped]";

    public RunEventRecord {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        if (sequence < 1) {
            // Not a lesser event: an event that cannot be placed in the order that IS the transcript.
            throw new IllegalArgumentException("a run event's sequence starts at 1, was " + sequence);
        }
        if (kind.isBlank()) {
            throw new IllegalArgumentException("a run event must name its kind");
        }
        at = at == null ? Instant.EPOCH : at;
        // Clipped rather than refused. Refusing loses the transcript line entirely, which is worse
        // than showing a bounded prefix of it — and says so, because a reader must not take a
        // truncated tool call for the whole of what the agent did.
        text = text == null ? "" : clip(text);
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT_CHARS
                ? text
                : text.substring(0, MAX_TEXT_CHARS - CLIPPED.length()) + CLIPPED;
    }
}
