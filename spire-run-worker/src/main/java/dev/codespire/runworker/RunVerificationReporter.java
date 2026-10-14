package dev.codespire.runworker;

import dev.codespire.contract.event.RunVerification;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Sends a verification on {@code cs.run-verifications} and waits for the broker's ack (M4). */
@ApplicationScoped
public class RunVerificationReporter {
    private static final Logger LOG = Logger.getLogger(RunVerificationReporter.class);

    @Inject @Channel("run-verifications-out") Emitter<Record<String, RunVerification>> verifications;
    @ConfigProperty(name = "spire.run.result-ack-seconds") long ackSeconds;

    /** @return whether the broker acknowledged it; false leaves the row unsent for the next sweep */
    public boolean report(RunVerification result) {
        try {
            verifications.send(Record.of(result.runId(), result)).toCompletableFuture().get(ackSeconds, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException notSent) {
            LOG.warnf("verification for %s was not acknowledged (%s); it is resent on the next sweep",
                    result.runId(), notSent.getClass().getSimpleName());
            return false;
        }
    }
}
