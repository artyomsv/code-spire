package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunVerification;
import dev.codespire.runtime.PublicationKey;
import dev.codespire.runtime.PublicationRuntime;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.VerifyRun;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks a held build with the operator's commands (M4, spec §3). Runs on the ordered work channel, so a verify
 * takes the worker's one execution slot like a build. The claim commits before the ack and before any
 * container; the result is stored before it is sent; a restart reports a verify it lost as could-not-run.
 */
@ApplicationScoped
public class WorkVerifyWorker {
    private static final Logger LOG = Logger.getLogger(WorkVerifyWorker.class);
    private static final VerifyRun COULD_NOT_RUN = new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), false);
    private static final VerifyRun NOT_RUN = new VerifyRun(null, VerifyRun.Prepare.PREPARED, List.of(), false);
    private static final VerifyRun CHECKPOINT_MISSING = new VerifyRun(null, VerifyRun.Prepare.CHECKPOINT_MISSING, List.of(), false);

    @Inject WorkVerifyStore store;
    @Inject WorkRunStore runs;
    @Inject RunUnitBuilder builder;
    @Inject RunRuntime runtime;
    @Inject RunVerificationReporter reporter;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public CompletionStage<Void> execute(Message<RunCommand> message, RunCommand.VerifyWork command) {
        // Marked active BEFORE the claim commits, so the recovery sweep can never see this row 'running' with
        // no owner and report a live verify as could-not-run.
        active.add(command.attemptId());
        boolean claimed;
        try {
            claimed = store.claim(command); // The claim commits before the ack: a redelivery runs nothing.
        } catch (RuntimeException unrecorded) {
            active.remove(command.attemptId());
            throw unrecorded;
        }
        message.ack().toCompletableFuture().join();
        if (!claimed) {
            active.remove(command.attemptId());
            LOG.infof("verify %s for %s already claimed; a redelivery", command.attemptId(), command.runId());
            flush();
            return CompletableFuture.completedFuture(null);
        }
        try {
            store.finish(new RunVerification.RunWorkVerified(command.runId(), command.work(), VerifyOutcomes.classify(command, observe(command))));
        } finally {
            active.remove(command.attemptId());
        }
        flush();
        return CompletableFuture.completedFuture(null);
    }

    private VerifyRun observe(RunCommand.VerifyWork command) {
        if (command.commands().isEmpty()) return NOT_RUN;
        if (!(runtime instanceof PublicationRuntime publication)) return COULD_NOT_RUN;
        Optional<WorkRunStore.Held> held = runs.find(command.runId()).filter(row -> row.execution().work().equals(command.work()));
        if (held.isEmpty()) return CHECKPOINT_MISSING;
        RunCommand.ExecuteWorkRun execution = held.orElseThrow().execution();
        if (runs.revoked(execution)) return COULD_NOT_RUN;
        Optional<RunHandle> unit = localUnit(command.runId());
        if (unit.isEmpty()) return CHECKPOINT_MISSING;
        try {
            return publication.verifyHeld(unit.orElseThrow(), new PublicationKey(command.work().publicationKey()),
                    builder.verifyUnit(execution, command), () -> !runs.revoked(execution));
        } catch (RuntimeException failure) {
            // Class only: a message can quote the unit's decrypted environment.
            LOG.warnf("verify %s for %s could not run (%s)", command.attemptId(), command.runId(), failure.getClass().getSimpleName());
            return COULD_NOT_RUN;
        } finally {
            removeQuietly(publication, unit.orElseThrow(), command.attemptId());
        }
    }

    @Scheduled(every = "${spire.run.work-recovery:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void recover() {
        for (RunCommand.VerifyWork stranded : store.running()) {
            if (active.contains(stranded.attemptId())) continue;
            if (runtime instanceof PublicationRuntime publication)
                localUnit(stranded.runId()).ifPresent(unit -> removeQuietly(publication, unit, stranded.attemptId()));
            // A verify this process did not finish proves nothing about the code: never inferred passed.
            store.finish(new RunVerification.RunWorkVerified(stranded.runId(), stranded.work(),
                    VerifyOutcomes.classify(stranded, stranded.commands().isEmpty() ? NOT_RUN : COULD_NOT_RUN)));
        }
        flush();
    }

    public void flush() {
        for (RunVerification.RunWorkVerified result : store.unsent()) {
            if (!reporter.report(result)) return;
            store.sent(result.verification().attemptId());
        }
    }

    private Optional<RunHandle> localUnit(String runId) {
        return runtime.discoverUnits().stream().filter(handle -> handle.runId().equals(runId)).findFirst();
    }

    private static void removeQuietly(PublicationRuntime publication, RunHandle unit, UUID attempt) {
        try {
            publication.removeVerify(unit, attempt);
        } catch (RuntimeException leftover) {
            LOG.warnf("verify %s resources were not removed (%s); destroyHeld removes them with the build",
                    attempt, leftover.getClass().getSimpleName());
        }
    }
}
