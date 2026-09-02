package dev.codespire.orchestrator.pipeline;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a failed publish might have landed anyway (FR-F10).
 *
 * <p>This is the fact the factory's dispatch acts on, and it decides money. If the record never left,
 * a retry is free and the run should be re-armable. If it might be on the partition, a retry starts a
 * second agent on the same branch and pays for the model twice — so an unacknowledged send has to be
 * treated as unresolved rather than as a definite miss.
 *
 * <p>The path this replaces reported all three outcomes as one {@code IllegalStateException}, so the
 * caller had to guess, and it guessed optimistically.
 */
class BrokerAckFailureTest {

    private static final String WHAT = "run command ExecuteRun for run::github:TEST-acme/app:s:1";

    @Test
    void aWaitThatElapsedSaysNothingAboutTheRecord() {
        // Our own patience ran out. The producer may still be retrying, and the append may already
        // have happened -- this is a fact about us, not about the partition.
        BrokerAckFailure failure = BrokerAckFailure.notAcknowledged("No broker ack within 10s for " + WHAT, null);

        assertTrue(failure.mayHaveLanded());
    }

    @Test
    void aProducerTimeoutIsAmbiguousBecauseTheClientCallsItRetriable() {
        // The Kafka producer reports a lost acknowledgement as a retriable timeout, and "retriable"
        // is precisely the client saying it does not know whether the append happened.
        BrokerAckFailure failure = BrokerAckFailure.rejected(WHAT, new TimeoutException("no leader ack"));

        assertTrue(failure.mayHaveLanded());
    }

    @Test
    void aRecordTheBrokerCouldNeverAcceptDefinitelyDidNotLand() {
        // Decided before anything reaches a partition, so the run definitely did not start and
        // re-arming its row is free. This is the ONLY direction in which certainty is available.
        BrokerAckFailure failure = BrokerAckFailure.rejected(WHAT, new RecordTooLargeException("too big"));

        assertFalse(failure.mayHaveLanded());
    }

    @Test
    void anAuthorizationFailureDefinitelyDidNotLand() {
        BrokerAckFailure failure = BrokerAckFailure.rejected(WHAT, new TopicAuthorizationException("denied"));

        assertFalse(failure.mayHaveLanded());
    }

    @Test
    void anUnrecognisedCauseIsTreatedAsAmbiguousOnlyIfTheClientSaysSo() {
        // The default is NOT "assume ambiguous for anything unknown": a cause the client does not
        // call retriable is one it decided locally, which means nothing was sent. Being wrong here
        // costs an operator one manual resolution; being wrong the other way costs a duplicate run,
        // which is why the classification is delegated to the client rather than guessed.
        BrokerAckFailure failure = BrokerAckFailure.rejected(WHAT, new IllegalArgumentException("serializer"));

        assertFalse(failure.mayHaveLanded());
    }

    @Test
    void everyFailureStaysAnIllegalStateExceptionForTheCallersThatAlreadyCatchIt() {
        // The review pipeline catches IllegalStateException from this same helper and must keep
        // behaving identically; only a caller that asks the new question gets a new answer.
        assertInstanceOf(IllegalStateException.class,
                BrokerAckFailure.rejected(WHAT, new RecordTooLargeException("too big")));
        assertInstanceOf(IllegalStateException.class, BrokerAckFailure.notAcknowledged("timed out", null));
    }
}
