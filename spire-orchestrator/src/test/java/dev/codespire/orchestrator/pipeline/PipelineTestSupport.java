package dev.codespire.orchestrator.pipeline;

import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Plain fakes for unit-testing pipeline components without a container. */
final class PipelineTestSupport {

    private PipelineTestSupport() {
    }

    /** Collects sent payloads instead of delivering them. */
    static final class RecordingEmitter<T> implements Emitter<T> {

        final List<T> sent = new ArrayList<>();

        @Override
        public CompletionStage<Void> send(T payload) {
            sent.add(payload);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends Message<? extends T>> void send(M message) {
            sent.add(message.getPayload());
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

    /** Collects timeline entries without touching WebSockets. */
    static final class RecordingTimeline extends TimelineBroadcaster {

        final List<String> entries = new ArrayList<>();

        @Override
        public void record(String lane, String type, String reviewId, String detail) {
            entries.add(lane + "/" + type + "/" + detail);
        }
    }
}
