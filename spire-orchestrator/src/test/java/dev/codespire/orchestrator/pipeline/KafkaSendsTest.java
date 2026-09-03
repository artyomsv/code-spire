package dev.codespire.orchestrator.pipeline;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownServerException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam where a publish failure is classified, which nothing exercised.
 *
 * <p>A review proved the gap rather than arguing it: rewriting the elapsed-wait branch to report a
 * definite miss — verbatim the duplicate-run behaviour FR-F10 exists to remove — left all 949 tests
 * in this module passing. {@code BrokerAckFailureTest} calls the two factory methods directly, and
 * the resource tests mock the emitter and throw a ready-made failure, so the code that actually
 * CHOOSES between them was reachable from nothing.
 *
 * <p>That is the shape this project keeps paying for: a rule tested on both sides of the one line
 * that applies it.
 */
class KafkaSendsTest {

    private static final String WHAT = "run command ExecuteRun for run::github:TEST-acme/app:s:1";

    /** Short, so the elapsed-wait branch costs milliseconds rather than the shipped ten seconds. */
    private static final Duration BRIEF = Duration.ofMillis(50);

    @Test
    void anAcknowledgedSendReturnsQuietly() {
        // The half that keeps every assertion below from being satisfied by "it always throws".
        KafkaSends.awaitAck(CompletableFuture.completedFuture(null), WHAT, BRIEF);
    }

    @Test
    void aWaitThatElapsesIsAmbiguousRatherThanADefiniteMiss() {
        // THE mutation that survived. The producer may still be retrying and the append may already
        // have happened, so re-arming the run on this would be how a second agent reaches the branch.
        BrokerAckFailure failure = assertThrows(BrokerAckFailure.class,
                () -> KafkaSends.awaitAck(new CompletableFuture<>(), WHAT, BRIEF));

        assertTrue(failure.mayHaveLanded());
        assertTrue(failure.getMessage().contains("No broker ack"));
    }

    @Test
    void aRejectionThatCouldOnlyHappenBeforeTheSendIsCertain() {
        BrokerAckFailure failure = assertThrows(BrokerAckFailure.class,
                () -> KafkaSends.awaitAck(
                        CompletableFuture.failedFuture(new RecordTooLargeException("too big")), WHAT, BRIEF));

        assertFalse(failure.mayHaveLanded());
    }

    @Test
    void aProducerTimeoutReachesTheClassifierAsTheBrokersOwnCause() {
        // The nack carries the producer's exception RAW -- SmallRye does not wrap it -- so this is
        // what the classifier really sees, and it must not be read as "never sent".
        BrokerAckFailure failure = assertThrows(BrokerAckFailure.class,
                () -> KafkaSends.awaitAck(
                        CompletableFuture.failedFuture(new TimeoutException("no leader ack")), WHAT, BRIEF));

        assertTrue(failure.mayHaveLanded());
    }

    @Test
    void aBrokerFaultDuringTheAppendIsAmbiguousThoughItIsNotRetriable() {
        // The case that made the old `instanceof RetriableException` test wrong. The broker's own
        // "something went wrong" says nothing about whether the append happened, and it is not a
        // RetriableException -- so the previous classifier called it a definite miss and made the
        // run re-armable.
        BrokerAckFailure failure = assertThrows(BrokerAckFailure.class,
                () -> KafkaSends.awaitAck(
                        CompletableFuture.failedFuture(new UnknownServerException("boom")), WHAT, BRIEF));

        assertTrue(failure.mayHaveLanded());
    }
}
