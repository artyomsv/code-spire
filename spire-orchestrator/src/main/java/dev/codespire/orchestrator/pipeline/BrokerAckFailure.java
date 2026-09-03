package dev.codespire.orchestrator.pipeline;

import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;

import java.util.List;

/**
 * A publish that did not come back acknowledged, and whether the record might have landed anyway.
 *
 * <p><b>The distinction is the whole point, and it is not a detail of error reporting.</b> A send can
 * fail in two ways that look identical to the caller and mean opposite things. The broker can refuse
 * the record before anything is appended — a serializer that threw, a payload over the topic's limit,
 * an authorization failure — in which case retrying is free. Or the acknowledgement can simply not
 * arrive, in which case the producer may still be retrying, the record may already be on the
 * partition, and a "retry" starts a second one.
 *
 * <p>For a factory run that difference is money and a second agent on the same branch. Collapsing
 * both into one exception, as this path did, forced the caller to guess — and it guessed the
 * optimistic way, recording every unacknowledged dispatch as one that definitely failed.
 *
 * <p><b>Ambiguity is the default, and it is enforced by an allowlist rather than by a test.</b> The
 * first version asked {@code cause instanceof RetriableException} and called anything else a definite
 * miss. That reads as delegating the judgement to the client, and it is the wrong question:
 * "retriable" answers <em>is a retry safe?</em>, never <em>did the append happen?</em>. Two reviews
 * disassembled the shipped Kafka client and found real non-retriable exceptions raised after a record
 * is on the wire — a producer closed mid-send fails every in-flight batch with a plain
 * {@code KafkaException}, {@code UnknownServerException} is the broker's own "something went wrong"
 * during append, and {@code DuplicateSequenceException} literally means the broker already has the
 * record. Every one of them was being reported as "safe to retry".
 *
 * <p>So the set below is exceptions that <b>cannot</b> be raised once a record has reached a
 * partition, and everything not on it — including anything a future client version adds — is
 * ambiguous. That is what "ambiguity is the default" has to mean to be worth anything: the unknown
 * case must fall on the safe side without anyone remembering to put it there.
 *
 * <p>Extends {@link IllegalStateException} deliberately: every existing caller on the review path
 * catches that and must keep behaving exactly as it did. Only a caller that asks the new question
 * gets a different answer.
 */
public class BrokerAckFailure extends IllegalStateException {

    /**
     * Failures decided before any record reaches a partition.
     *
     * <p>Membership is a claim about WHEN the client can raise it, not about whether a retry would
     * succeed — which is the distinction the first version of this class got wrong. Each of these is
     * either evaluated locally before the send or is the broker refusing the append outright.
     *
     * <p>Deliberately NOT here: {@code IllegalArgumentException} and other unqualified JDK types. An
     * unrecognised exception is exactly the case the allowlist exists to send down the safe branch,
     * and adding a broad type "because a serializer probably threw it" reinstates the guess.
     */
    private static final List<Class<? extends Throwable>> NEVER_REACHED_A_PARTITION = List.of(
            SerializationException.class,
            RecordTooLargeException.class,
            RecordBatchTooLargeException.class,
            InvalidTopicException.class,
            AuthorizationException.class,
            AuthenticationException.class,
            InvalidConfigurationException.class);

    /**
     * Not {@code transient}, and that is deliberate on a {@link java.io.Serializable} type.
     *
     * <p>A transient field deserializes to {@code false} — "definitely did not land" — which is the
     * re-armable direction and the expensive one to be wrong about. The default has to be the safe
     * answer on every path, including the one nobody expects to take.
     */
    private final boolean mayHaveLanded;

    BrokerAckFailure(String message, Throwable cause, boolean mayHaveLanded) {
        super(message, cause);
        this.mayHaveLanded = mayHaveLanded;
    }

    /**
     * Whether the record might be on the topic despite this failure.
     *
     * <p>True for a wait that timed out or was interrupted — neither says anything about the record,
     * only about our patience — and true for any producer failure the client does not raise strictly
     * before the send.
     */
    public boolean mayHaveLanded() {
        return mayHaveLanded;
    }

    /**
     * A producer's own rejection, classified by whether it can only have happened before the send.
     *
     * <p>A null cause is ambiguous, like anything else unrecognised.
     */
    public static BrokerAckFailure rejected(String description, Throwable cause) {
        boolean neverSent = cause != null
                && NEVER_REACHED_A_PARTITION.stream().anyMatch(type -> type.isInstance(cause));
        return new BrokerAckFailure("Broker rejected " + description, cause, !neverSent);
    }

    /** Our own wait elapsed. The producer may still be retrying, so this says nothing about the record. */
    public static BrokerAckFailure notAcknowledged(String message, Throwable cause) {
        return new BrokerAckFailure(message, cause, true);
    }
}
