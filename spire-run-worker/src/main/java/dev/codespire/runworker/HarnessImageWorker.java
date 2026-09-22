package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.HarnessImageCommand;
import dev.codespire.contract.event.HarnessImageResult;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Answers "what does this agent image declare about itself" (M3.5 part M).
 *
 * <p>The worker is asked because it is the only component that can reach an agent image at all: the
 * orchestrator has no container runtime, and on Kubernetes never will. Reading labels uses the same
 * authenticated pull a run uses, so an image a run could start is an image this can describe.
 */
@ApplicationScoped
public class HarnessImageWorker {

    private static final Logger LOG = Logger.getLogger(HarnessImageWorker.class);

    @Inject RunRuntime runtime;
    @Inject ObjectMapper mapper;

    @Inject @Channel("harness-image-results-out")
    Emitter<Record<String, HarnessImageResult>> results;

    @ConfigProperty(name = "spire.run.result-ack-seconds")
    long ackSeconds;

    /*
     * In order, and keyed by harness on the way out: the orchestrator keeps the LAST answer for a
     * harness, so two answers for one harness must arrive in the order they were given. Unordered
     * processing, or a random key spreading answers over partitions, let an older answer land after a
     * newer one and replace it (review of PR #167). The questions are few, so order costs nothing.
     */
    @Incoming("harness-image-commands-in")
    @Blocking
    public CompletionStage<Void> onCommand(Message<HarnessImageCommand> message) {
        if (message.getPayload() instanceof HarnessImageCommand.Describe describe) {
            publish(describe(describe));
        }
        return message.ack();
    }

    HarnessImageResult.Described describe(HarnessImageCommand.Describe command) {
        Map<String, String> labels;
        try {
            labels = runtime.imageLabels(command.image());
        } catch (RuntimeException unreachable) {
            // Named, not rethrown: an image that cannot be pulled is an answer the screen has to give
            // ("this image is not available"), and a thrown exception here would only dead-letter it.
            LOG.warnf("the image for harness %s could not be read (%s)", command.harness(),
                    unreachable.getClass().getSimpleName());
            return new HarnessImageResult.Described(command.requestId(), command.harness(), command.image(),
                    HarnessImageResult.Status.IMAGE_UNAVAILABLE, List.of());
        }
        ModelCatalogueLabel.Read read = ModelCatalogueLabel.of(labels, mapper);
        return new HarnessImageResult.Described(command.requestId(), command.harness(), command.image(),
                read.status(), read.models());
    }

    private static String keyOf(HarnessImageResult result) {
        return switch (result) {
            case HarnessImageResult.Described described -> described.harness();
        };
    }

    private void publish(HarnessImageResult result) {
        try {
            results.send(Record.of(keyOf(result), result)).toCompletableFuture().get(ackSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException undelivered) {
            // The orchestrator asks again on its own schedule, so a lost answer costs one interval.
            LOG.warnf(undelivered, "the description of image %s could not be published", result.requestId());
        }
    }
}
