package dev.codespire.e2e.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwaitTest {

    @Test
    void returnsTheValueOnceTheProbeIsSatisfied() {
        AtomicInteger calls = new AtomicInteger();
        String found = Await.until("S1 comments posted", Duration.ofSeconds(30),
                () -> calls.incrementAndGet() < 3 ? Optional.empty() : Optional.of("ready"));

        assertEquals("ready", found);
        assertTrue(calls.get() >= 3);
    }

    @Test
    void failureNamesTheStepAndTheDeadline() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> Await.until("S9 verdict RESOLVED", Duration.ofMillis(600), Optional::empty));

        assertTrue(error.getMessage().contains("S9 verdict RESOLVED"),
                "the step name is the first thing a nightly failure report must carry: "
                        + error.getMessage());
    }

    /**
     * A probe may legitimately throw while the system converges — a row that does not exist yet, a
     * thread not yet created. Aborting on the first one would turn every ordinary race into a failure.
     */
    @Test
    void keepsProbingThroughATransientProbeError() {
        AtomicInteger calls = new AtomicInteger();
        String found = Await.until("transient", Duration.ofSeconds(30), () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("row not there yet");
            }
            return Optional.of("ready");
        });

        assertEquals("ready", found);
    }

    @Test
    void failureCarriesTheLastProbeError() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> Await.until("always broken", Duration.ofMillis(600), () -> {
                    throw new IllegalStateException("relation does not exist");
                }));

        assertTrue(error.getMessage().contains("relation does not exist"),
                "a probe that only ever threw must say WHY, not just that it timed out: "
                        + error.getMessage());
    }

    /**
     * The absence contract. Checking "nothing happened" immediately passes against a system that has
     * simply not got round to it yet, which is how S11 would have asserted nothing at all.
     */
    @Test
    void absenceFailsWhenTheCountRisesDuringTheQuietPeriod() {
        AtomicLong count = new AtomicLong(2);
        assertThrows(AssertionError.class,
                () -> Await.absent("S11 no new review", Duration.ofSeconds(8), count::incrementAndGet));
    }

    @Test
    void absencePassesWhenTheCountHolds() {
        AtomicLong count = new AtomicLong(2);
        Await.absent("S11 no new review", Duration.ofSeconds(8), count::get);
    }
}
