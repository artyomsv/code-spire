package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.HarnessImageCommand;
import dev.codespire.contract.event.HarnessImageResult;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orchestrator keeps the LAST answer it hears for a harness, so the answers for one harness must
 * reach it in the order they were given (review of PR #167).
 */
class HarnessImageWorkerTest {

    @Test
    void anAnswerIsKeyedByItsHarnessSoOneHarnessKeepsOnePartition() {
        List<Record<String, HarnessImageResult>> sent = new ArrayList<>();
        HarnessImageWorker worker = new HarnessImageWorker();
        // Only the label read is reached; any other runtime call returns null and would fail loudly.
        worker.runtime = (RunRuntime) Proxy.newProxyInstance(RunRuntime.class.getClassLoader(), new Class<?>[] { RunRuntime.class },
                (proxy, method, args) -> method.getName().equals("describeImage")
                        ? new dev.codespire.runtime.ImageDescription("TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000", Map.of()) : null);
        worker.mapper = new ObjectMapper();
        worker.ackSeconds = 5;
        worker.results = new Capturing(sent);

        worker.onCommand(Message.of(new HarnessImageCommand.Describe("TEST-request-1", "codex", "TEST-image")));

        assertEquals(1, sent.size());
        assertEquals("codex", sent.getFirst().key());
        // The exact image read travels with the answer, so a run can use it instead of the tag.
        assertEquals("TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000",
                ((HarnessImageResult.Described) sent.getFirst().value()).pinnedImage());
    }

    /** One partition keeps order only if the worker does not answer two of its messages at once. */
    @Test
    void theQuestionsAreAnsweredOneAtATime() throws NoSuchMethodException {
        Blocking blocking = HarnessImageWorker.class.getMethod("onCommand", Message.class).getAnnotation(Blocking.class);
        assertTrue(blocking.ordered());
    }

    private record Capturing(List<Record<String, HarnessImageResult>> sent) implements Emitter<Record<String, HarnessImageResult>> {
        @Override
        public CompletionStage<Void> send(Record<String, HarnessImageResult> record) {
            sent.add(record);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends Message<? extends Record<String, HarnessImageResult>>> void send(M message) {
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
