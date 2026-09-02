package dev.codespire.orchestrator.pipeline;

import org.apache.kafka.common.errors.RetriableException;

/**
 * A publish that did not come back acknowledged, and whether the record might have landed anyway.
 *
 * <p><b>The distinction is the whole point, and it is not a detail of error reporting.</b> A send can
 * fail in two ways that look identical to the caller and mean opposite things. The broker can refuse
 * the record — a serializer that threw, a payload over the topic's limit, an authorization failure —
 * in which case nothing was appended and retrying is free. Or the acknowledgement can simply not
 * arrive in time, in which case the producer may still be retrying, the record may already be on the
 * partition, and a "retry" starts a second one.
 *
 * <p>For a factory run that difference is money and a second agent on the same branch. Collapsing
 * both into one exception, as this path did, forced the caller to guess — and it guessed the
 * optimistic way, recording every unacknowledged dispatch as one that definitely failed.
 *
 * <p><b>Ambiguity is the default.</b> {@link #mayHaveLanded()} answers true unless the cause proves
 * the record never left, because being wrong in that direction costs an operator one manual
 * resolution, while being wrong the other way spends real money on a duplicate run.
 *
 * <p>Extends {@link IllegalStateException} deliberately: every existing caller on the review path
 * catches that and must keep behaving exactly as it did. Only a caller that asks the new question
 * gets a different answer.
 */
public class BrokerAckFailure extends IllegalStateException {

    private final transient boolean mayHaveLanded;

    BrokerAckFailure(String message, Throwable cause, boolean mayHaveLanded) {
        super(message, cause);
        this.mayHaveLanded = mayHaveLanded;
    }

    /**
     * Whether the record might be on the topic despite this failure.
     *
     * <p>True for a wait that timed out or was interrupted — neither says anything about the
     * record, only about our patience — and true for a producer failure the client itself calls
     * retriable, since the Kafka producer reports a lost acknowledgement that way and the record may
     * well have been appended before it was lost.
     */
    public boolean mayHaveLanded() {
        return mayHaveLanded;
    }

    /**
     * A producer's own rejection, classified by whether the client calls it retriable.
     *
     * <p>{@code RetriableException} covers the timeout the producer raises when it gives up waiting
     * for the leader's acknowledgement, and that is exactly the ambiguous case: the append may have
     * happened. Everything else — a serializer that threw, a record too large, a topic authorization
     * failure — is decided before anything reaches a partition.
     */
    public static BrokerAckFailure rejected(String description, Throwable cause) {
        return new BrokerAckFailure("Broker rejected " + description, cause,
                cause instanceof RetriableException);
    }

    /** Our own wait elapsed. The producer may still be retrying, so this says nothing about the record. */
    public static BrokerAckFailure notAcknowledged(String message, Throwable cause) {
        return new BrokerAckFailure(message, cause, true);
    }
}
