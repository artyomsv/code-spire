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
        return new HarnessSignInCommand.Start("TEST-sign-in", "codex", "TEST-image", 840, at);
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

    /** A start with no press time predates this change, so it can only be a replay. */
    @Test
    void aStartWithNoPressTimeOpensNoUnit() {
        worker().onCommand(Message.of(new HarnessSignInCommand.Start("TEST-sign-in", "codex", "TEST-image", 840)));

        assertEquals(0, sent.size());
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
