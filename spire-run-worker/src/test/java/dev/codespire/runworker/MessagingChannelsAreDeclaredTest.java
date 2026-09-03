package dev.codespire.runworker;

import io.smallrye.reactive.messaging.annotations.Blocking;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control channel's separation from the work channel, which nothing else asserts.
 *
 * <p>Every case in {@link RunControlListenerTest} calls {@code onControl} directly, so the listener's
 * behaviour is well covered and its WIRING is not covered at all. A review measured it: repointing
 * {@code @Incoming} at {@code run-commands-in} — the very channel the split exists to escape — left
 * all sixteen of those cases green, while cancel went back to being the no-op the whole task was
 * written to remove. The separation lives entirely in one annotation value and in two blocks of
 * {@code application.yml}, and neither was read by any test.
 *
 * <p>Same shape as {@link ScheduledWorkIsDeclaredTest}, for the same reason: what can be deleted by
 * accident is the declaration, so the declaration is what gets asserted.
 */
class MessagingChannelsAreDeclaredTest {

    private static final String CONTROL_CHANNEL = "run-control-in";

    private static final String WORK_CHANNEL = "run-commands-in";

    @Test
    void controlIsReadFromItsOwnChannelAndNotTheWorkChannel() {
        Incoming incoming = onControl().getAnnotation(Incoming.class);

        assertNotNull(incoming, "without this the listener consumes nothing and cancel is a no-op");
        assertEquals(CONTROL_CHANNEL, incoming.value(),
                "on the work channel a cancel queues behind the run it means to stop, so it is read"
                        + " only once that run has already finished");
    }

    /**
     * The annotation TYPE is the assertion, not merely its presence.
     *
     * <p>{@code io.smallrye.common.annotation.Blocking} carries no {@code ordered} attribute, and
     * Quarkus maps its absence to ordered execution. The class shipped with that one while its own
     * javadoc claimed the opposite, so every control record was processed one at a time: a cancel
     * hung on an unresponsive daemon blocked every later cancel, for every other run — and an
     * unresponsive daemon is exactly when an operator is trying to cancel things.
     */
    @Test
    void controlRecordsAreNotProcessedOneAtATime() {
        Blocking blocking = onControl().getAnnotation(Blocking.class);

        assertNotNull(blocking, "the runtime call must stay off the event loop");
        assertFalse(blocking.ordered(),
                "two cancels for different runs must not wait on each other; a hung one would"
                        + " otherwise hold the channel that every other cancel arrives on");
    }

    @Test
    void theTwoChannelsResolveToDifferentTopicsAndDifferentGroups() {
        String yaml = applicationYaml();

        assertTrue(yaml.contains("topic: cs.run-control"), "control needs its own topic");
        assertTrue(yaml.contains("topic: cs.run-commands"), "work keeps its own topic");
        // Distinct group ids, and not by luck: sharing one would make Kafka split the two channels'
        // partitions across the same members.
        assertTrue(yaml.contains("id: spire-run-worker-control-${quarkus.uuid}"),
                "the control group must be PER INSTANCE. A run's handle lives only in the process"
                        + " holding its containers, so a cancel is actionable on exactly one replica"
                        + " -- under a shared group Kafka hands the record to one member, chosen"
                        + " independently of the work topic's assignment, and the cancel lands on a"
                        + " replica that is not running the run and is dropped as a late one");
    }

    @Test
    void theControlChannelDoesNotPrefetch() {
        // A prefetched cancel ages behind the one in hand, because SmallRye stamps a record's age
        // when it is POLLED rather than when its handler starts. RunAckBudget refuses to start
        // without these; this asserts they are actually written, since the budget reads config and
        // config has defaults.
        String control = channelBlock(applicationYaml(), CONTROL_CHANNEL);

        assertTrue(control.contains("records: 1"), "max.poll.records must be 1");
        assertTrue(control.contains("max-queue-size-factor: 1"), "no prefetch queue");
        assertTrue(control.contains("unprocessed-record-max-age"),
                "an explicit threshold, or the channel inherits the 60s default the work channel was"
                        + " raised from after the incident that guard exists to prevent");
    }

    @Test
    void executionStaysOnTheOrderedWorkChannel() {
        // The other half. Control being unordered is only safe because execution is not: the
        // launcher holds the work channel for a run's whole duration by design.
        Incoming incoming = onCommand().getAnnotation(Incoming.class);

        assertNotNull(incoming);
        assertEquals(WORK_CHANNEL, incoming.value());
    }

    private static Method onControl() {
        return method(RunControlListener.class, "onControl", dev.codespire.contract.command.RunCommand.class);
    }

    private static Method onCommand() {
        for (Method method : RunDispatcher.class.getMethods()) {
            if (method.isAnnotationPresent(Incoming.class)) {
                return method;
            }
        }
        throw new IllegalStateException("RunDispatcher consumes nothing");
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getSimpleName() + "." + name + "() is gone", e);
        }
    }

    /** The shipped config, read as a resource so the test sees what the application will see. */
    private static String applicationYaml() {
        try (InputStream in = RunControlListener.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("application.yml could not be read", e);
        }
    }

    /** From a channel's own key to the next one at the same indentation. */
    private static String channelBlock(String yaml, String channel) {
        int start = yaml.indexOf("      " + channel + ":");
        assertTrue(start >= 0, channel + " is not declared");
        int next = yaml.indexOf("\n      ", yaml.indexOf('\n', start + 1));
        while (next >= 0 && yaml.startsWith("\n        ", next)) {
            next = yaml.indexOf("\n      ", next + 1);
        }
        return next < 0 ? yaml.substring(start) : yaml.substring(start, next);
    }
}
