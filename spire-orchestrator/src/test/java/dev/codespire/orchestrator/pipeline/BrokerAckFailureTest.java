package dev.codespire.orchestrator.pipeline;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.DuplicateSequenceException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownServerException;
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
    void aProducerTimeoutIsAmbiguous() {
        // The producer reports a lost acknowledgement this way, and the append may well have
        // happened before the ack was lost. It reaches the safe branch by not being on the
        // never-sent list -- not by being "retriable", which was the old test's reasoning and is a
        // different question.
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

    /**
     * The default, and the assertion that replaced its own inverse.
     *
     * <p>The first version of this test asserted the opposite and argued for it — that a cause the
     * client does not call retriable "was decided locally, which means nothing was sent", and that
     * this delegated the judgement rather than guessing it. That reads as principled and conflates
     * two questions: {@code RetriableException} answers <em>is a retry safe?</em>, never <em>did the
     * append happen?</em>. So the test locked in a classification that made unknown failures
     * re-armable, which is the duplicate-run hazard the class exists to prevent.
     */
    @Test
    void anUnrecognisedCauseIsAmbiguous() {
        BrokerAckFailure failure = BrokerAckFailure.rejected(WHAT, new IllegalArgumentException("who knows"));

        assertTrue(failure.mayHaveLanded(),
                "an exception nobody classified says nothing about the record, so it must fall on the"
                        + " side that costs an operator a decision rather than the side that spends money");
    }

    @Test
    void aNullCauseIsAmbiguous() {
        assertTrue(BrokerAckFailure.rejected(WHAT, null).mayHaveLanded());
    }

    /**
     * The three the reviews disassembled the shipped client to find, each non-retriable and each
     * raisable after a record is on the wire.
     */
    @Test
    void aNonRetriableFailureRaisedAfterTheSendIsStillAmbiguous() {
        // The broker's own "something went wrong" during append.
        assertTrue(BrokerAckFailure.rejected(WHAT, new UnknownServerException("boom")).mayHaveLanded());

        // A producer closed mid-send fails every in-flight batch, including ones already on the wire.
        assertTrue(BrokerAckFailure.rejected(WHAT,
                new KafkaException("Producer is closed forcefully.")).mayHaveLanded());

        // Kafka's own interrupt, which the sibling branch in KafkaSends already treats as ambiguous
        // when it arrives as a java.lang.InterruptedException. The same fact must not be classified
        // two ways six lines apart.
        assertTrue(BrokerAckFailure.rejected(WHAT, new InterruptException("interrupted")).mayHaveLanded());
    }

    /**
     * The one whose meaning is the opposite of how it was being read.
     *
     * <p>{@code DuplicateSequenceException} is the broker saying it ALREADY HAS this record, and the
     * old classifier reported it as "definitely did not land" — the single most backwards answer the
     * set could produce.
     */
    @Test
    void anExceptionMeaningTheBrokerAlreadyHasTheRecordIsNeverACertainMiss() {
        assertTrue(BrokerAckFailure.rejected(WHAT, new DuplicateSequenceException("already have it"))
                .mayHaveLanded());
    }

    @Test
    void certaintyIsNotLostForTheCausesThatDoProveIt() {
        // The other half. An allowlist that answered "ambiguous" to everything would pass every
        // assertion above and make the whole distinction useless.
        assertFalse(BrokerAckFailure.rejected(WHAT, new SerializationException("bad key")).mayHaveLanded());
        assertFalse(BrokerAckFailure.rejected(WHAT, new InvalidTopicException("no such topic")).mayHaveLanded());
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
