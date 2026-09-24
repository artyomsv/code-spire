package dev.codespire.runworker;

import dev.codespire.contract.command.HarnessSignInCommand;
import dev.codespire.contract.event.HarnessSignInResult;
import dev.codespire.runtime.SignInRuntime;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A start is replayed after a restart and re-sent when nobody picked it up, so its wait is counted from
 * the operator's press (review of PR #168). Plain unit tests: the paths under test end before any
 * container, and the runtime fails the test if it is reached.
 */
class HarnessSignInStartTimeTest {

    private final List<HarnessSignInResult> sent = new ArrayList<>();

    private HarnessSignInWorker worker() {
        HarnessSignInWorker worker = new HarnessSignInWorker();
        worker.runtime = (SignInRuntime) Proxy.newProxyInstance(SignInRuntime.class.getClassLoader(),
                new Class<?>[] { SignInRuntime.class },
                (proxy, method, args) -> { throw new AssertionError("no unit may be started: " + method.getName()); });
        worker.ackSeconds = 5;
        worker.results = new Capturing(sent);
        return worker;
    }

    private static HarnessSignInCommand.Start pressed(Instant at) {
        return new HarnessSignInCommand.Start("TEST-sign-in", "codex", "TEST-image", 840, at, 240);
    }

    /**
     * Opens no unit, and says nothing: with two workers, a late copy reporting "expired" would end a
     * sign-in the other worker is running (review of PR #168). The orchestrator ends unclaimed rows.
     */
    @Test
    void aStartWhoseWaitHasRunOutOpensNoUnitAndReportsNothing() {
        worker().onCommand(Message.of(pressed(Instant.now().minusSeconds(900))));

        assertEquals(0, sent.size());
    }

    /**
     * Most of the person's wait is left, but a code printed now would land after the orchestrator ended
     * the row (review of PR #168). The start window, not the wait, decides.
     */
    @Test
    void aStartPastItsWindowOpensNoUnitEvenWithWaitLeft() {
        worker().onCommand(Message.of(pressed(Instant.now().minusSeconds(200))));

        assertEquals(0, sent.size());
    }

    /** A start with no press time predates this change, so it can only be a replay. */
    @Test
    void aStartWithNoPressTimeOpensNoUnit() {
        worker().onCommand(Message.of(new HarnessSignInCommand.Start("TEST-sign-in", "codex", "TEST-image", 840)));

        assertEquals(0, sent.size());
    }

    /**
     * A failure before this worker owns a unit — a timeout, a refused create — says nothing about the
     * sign-in: another worker may be running it. So it reports nothing (review of PR #168).
     */
    @Test
    void aFailureBeforeOwnershipReportsNothing() {
        HarnessSignInWorker worker = worker();
        worker.harnesses = new HarnessRegistry();
        worker.runtime = (SignInRuntime) Proxy.newProxyInstance(SignInRuntime.class.getClassLoader(),
                new Class<?>[] { SignInRuntime.class }, (proxy, method, args) -> {
                    if (method.getName().equals("start")) throw new IllegalStateException("TEST connection reset");
                    throw new AssertionError("nothing else may be reached: " + method.getName());
                });

        worker.onCommand(Message.of(pressed(Instant.now())));

        assertEquals(0, sent.size());
    }

    /**
     * A unit admitted in time that then starts slowly — or whose process is paused — prints its code
     * after the window has closed. The row has been ended by then, so nothing may be published; the unit
     * is destroyed (review of PR #168).
     */
    @Test
    void aCodePrintedAfterTheWindowIsNotPublished() {
        HarnessSignInWorker worker = worker();
        worker.harnesses = new HarnessRegistry();
        Instant pressed = Instant.parse("2026-09-24T12:00:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(pressed);
        worker.clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        List<String> destroyed = new ArrayList<>();
        worker.runtime = (SignInRuntime) Proxy.newProxyInstance(SignInRuntime.class.getClassLoader(),
                new Class<?>[] { SignInRuntime.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "start" -> {
                        now.set(pressed.plusSeconds(600));
                        @SuppressWarnings("unchecked")
                        java.util.function.Consumer<String> lines = (java.util.function.Consumer<String>) args[1];
                        lines.accept("1. Open this link in your browser and sign in to your account");
                        lines.accept("   https://auth.openai.com/codex/device");
                        lines.accept("2. Enter this one-time code (expires in 15 minutes)");
                        lines.accept("   ABCD-12345");
                        yield new SignInRuntime.Handle("TEST-sign-in", "TEST-unit");
                    }
                    case "destroy" -> { destroyed.add("TEST-unit"); yield true; }
                    default -> throw new AssertionError("a late unit must not be waited on: " + method.getName());
                });

        worker.onCommand(Message.of(pressed(pressed)));

        assertEquals(0, sent.size(), "no code for a row already ended");
        assertEquals(List.of("TEST-unit"), destroyed);
    }

    /**
     * A slow start inside the window: the code is shown, but the time left for the person is measured
     * after the start, not before it, so the fourteen-minute limit is not stretched (review of PR #168).
     */
    @Test
    void theTimeLeftIsMeasuredAfterASlowStart() {
        HarnessSignInWorker worker = worker();
        worker.harnesses = new HarnessRegistry();
        Instant pressed = Instant.parse("2026-09-24T12:00:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(pressed);
        worker.clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        List<java.time.Duration> waited = new ArrayList<>();
        worker.runtime = (SignInRuntime) Proxy.newProxyInstance(SignInRuntime.class.getClassLoader(),
                new Class<?>[] { SignInRuntime.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "start" -> {
                        now.set(pressed.plusSeconds(150));
                        @SuppressWarnings("unchecked")
                        java.util.function.Consumer<String> lines = (java.util.function.Consumer<String>) args[1];
                        lines.accept("   https://auth.openai.com/codex/device");
                        lines.accept("   ABCD-12345");
                        yield new SignInRuntime.Handle("TEST-sign-in", "TEST-unit");
                    }
                    case "awaitExit" -> { waited.add((java.time.Duration) args[1]); yield new SignInRuntime.Exit.StillRunning(); }
                    case "destroy" -> true;
                    case "cancel" -> null;
                    default -> throw new AssertionError("unexpected: " + method.getName());
                });

        worker.onCommand(Message.of(pressed(pressed)));

        var prompted = (HarnessSignInResult.Prompted) sent.getFirst();
        assertEquals(pressed.plusSeconds(840), prompted.expiresAt(), "the approval time ends 840s after the press");
        assertEquals(List.of(java.time.Duration.ofSeconds(690)), waited);
    }

    /**
     * Time passes between any two readings of the clock. The countdown shown must still end exactly at
     * the approval deadline, never after it (review of PR #168).
     */
    @Test
    void theShownExpiryNeverPassesTheApprovalDeadline() {
        HarnessSignInWorker worker = worker();
        worker.harnesses = new HarnessRegistry();
        Instant pressed = Instant.parse("2026-09-24T12:00:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(pressed);
        java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();
        worker.clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            // After the unit starts, every reading is a second later than the one before it.
            @Override public Instant instant() { return ticking.get() ? now.updateAndGet(at -> at.plusSeconds(1)) : now.get(); }
        };
        worker.runtime = (SignInRuntime) Proxy.newProxyInstance(SignInRuntime.class.getClassLoader(),
                new Class<?>[] { SignInRuntime.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "start" -> {
                        now.set(pressed.plusSeconds(150));
                        ticking.set(true);
                        @SuppressWarnings("unchecked")
                        java.util.function.Consumer<String> lines = (java.util.function.Consumer<String>) args[1];
                        lines.accept("   https://auth.openai.com/codex/device");
                        lines.accept("   ABCD-12345");
                        yield new SignInRuntime.Handle("TEST-sign-in", "TEST-unit");
                    }
                    case "awaitExit" -> new SignInRuntime.Exit.StillRunning();
                    case "destroy" -> true;
                    case "cancel" -> null;
                    default -> throw new AssertionError("unexpected: " + method.getName());
                });

        worker.onCommand(Message.of(pressed(pressed)));

        var prompted = (HarnessSignInResult.Prompted) sent.getFirst();
        assertEquals(pressed.plusSeconds(840), prompted.expiresAt());
    }

    /** A replay of the start for a unit still running here must not fail that unit. */
    @Test
    void aReplayedStartForAUnitStillRunningIsIgnored() throws Exception {
        HarnessSignInWorker worker = worker();
        var running = HarnessSignInWorker.class.getDeclaredField("running");
        running.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SignInRuntime.Handle> units = (Map<String, SignInRuntime.Handle>) running.get(worker);
        units.put("TEST-sign-in", new SignInRuntime.Handle("TEST-unit", "TEST-unit"));

        worker.onCommand(Message.of(pressed(Instant.now().minusSeconds(900))));

        assertEquals(0, sent.size());
    }

    private record Capturing(List<HarnessSignInResult> sent)
            implements Emitter<Record<String, HarnessSignInResult>> {
        @Override
        public CompletionStage<Void> send(Record<String, HarnessSignInResult> record) {
            sent.add(record.value());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends Message<? extends Record<String, HarnessSignInResult>>> void send(M message) {
            throw new UnsupportedOperationException("the worker sends records, not messages");
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Exception e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean hasRequests() {
            return true;
        }
    }
}
