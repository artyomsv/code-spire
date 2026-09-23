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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    @Test
    void aStartWhoseWaitHasRunOutOpensNoUnitAndSaysItExpired() {
        worker().onCommand(Message.of(pressed(Instant.now().minusSeconds(900))));

        assertEquals(1, sent.size());
        HarnessSignInResult.Failed failed = assertInstanceOf(HarnessSignInResult.Failed.class, sent.getFirst());
        assertEquals(HarnessSignInResult.Failed.EXPIRED, failed.cause());
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
