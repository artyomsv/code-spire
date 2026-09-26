package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.*;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Held execution and publisher-only resumption, backed by durable claims and two independent result slots. */
@ApplicationScoped
public class WorkRunWorker {
    private static final Logger LOG=Logger.getLogger(WorkRunWorker.class);
    @Inject WorkRunStore store;
    @Inject RunClaimStore claims;
    @Inject WorkspaceLeases leases;
    @Inject RunRegistry registry;
    @Inject RunLauncher launcher;
    @Inject RunUnitBuilder builder;
    @Inject RunRuntime runtime;
    @Inject RunFailures failures;
    @Inject RunResultReporter results;
    @Inject RunTranscript transcript;
    @ConfigProperty(name="spire.run.orphan-stale-after-seconds") long staleAfterSeconds;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    /** Keys a publication's own credential apart from the build's, under the same run id. */
    static final String PUBLICATION_SECRETS=":publication";

    public CompletionStage<Void> execute(Message<RunCommand> message,RunCommand.ExecuteWorkRun command) {
        boolean claimed=store.claim(command); // No ack until the command and shared M2 execute slot commit together.
        message.ack().toCompletableFuture().join();
        if(!claimed){flushResults();return CompletableFuture.completedFuture(null);}
        String id=command.runId();
        active.add(id);
        try {
            RunResult result;
            if(claims.taken(id,RunDispatcher.CANCEL_SLOT) || store.revoked(command)) {
                result=failures.of(command.execution(),"CANCELLED","Cancelled before the held build started");
            } else if(!leases.take(id)) {
                result=failures.of(command.execution(),"WORKER_FAILED","No lease was taken; the held build was not started");
            } else {
                // Held until the retained workspace is released: a held unit's publisher runs later. Not
                // before the refusals above, which create nothing and would leave the entry held for ever.
                LiveSecrets.register(id,()->failures.scrubFor(command.execution()));
                // The topology is saved immediately before creation is attempted. Only a launch that
                // never got that far is proven to have created nothing: a create that fails part-way can
                // leave credential-bearing containers behind with no unit reported (review of PR #178).
                boolean[] creationAttempted={false};
                result=launcher.launchHeld(command,new HeldObserver(command),unit->{creationAttempted[0]=true;store.saveUnit(id,unit);});
                if(!creationAttempted[0])LiveSecrets.forget(id);
            }
            if(cancelled(id) || store.revoked(command)) result=cancelledResult(command,result);
            store.buildResult(result);
        } catch(RuntimeException failure) {
            // No raw exception text: it can quote the retained topology's decrypted environment.
            LOG.errorf("run %s: held execution could not be recorded (%s); retained for recovery",id,failure.getClass().getSimpleName());
        } finally {
            registry.forget(id);
            leases.preserve(id);
            active.remove(id);
        }
        flushResults();
        return CompletableFuture.completedFuture(null);
    }

    public void publish(RunCommand.PublishWorkRun request) {
        if(!(runtime instanceof PublicationRuntime publication))return;
        Optional<RunHandle> unit=localUnit(request.runId());
        if(unit.isEmpty() || !publication.publicationHeld(unit.orElseThrow()))return;
        if(!active.add(request.runId()))return;
        try {
            if(!store.claimPublication(request)) {
                LOG.warnf("run %s: publication permit was not claimed; binding, head, state, time or cancellation did not permit it",request.runId());
                return;
            }
            publishClaimed(store.find(request.runId()).orElseThrow(),unit.orElseThrow(),publication);
        } finally {active.remove(request.runId());}
        flushResults();
    }

    private void publishClaimed(WorkRunStore.Held held,RunHandle handle,PublicationRuntime publication) {
        String id=held.execution().runId();
        // A publisher may run in a later process than the build did, so its secrets are held again here —
        // and the permit's forge credential with them, which may have been rotated since the build.
        LiveSecrets.register(id,()->failures.scrubFor(held.execution().execution()));
        LiveSecrets.register(id+PUBLICATION_SECRETS,()->failures.scrubForPublication(held.execution().execution(),held.permit()));
        // Register the retained handle before publisher creation so control can find this run again.
        registry.register(id,held.execution().execution().harness(),handle,RunNotes.IGNORING);
        try {
            RunResult result;
            {
                var spec=builder.publication(held.unit(),held.execution(),held.permit());
                PublisherOutcome outcome=new PublisherOutcome();
                Finalization end=publication.publishHeld(handle,new PublicationKey(held.execution().work().publicationKey()),
                        held.permit().permit().deliveryAttemptId(),spec,outcome::accept,()->publicationAllowed(held.execution()));
                if(outcome.pushedRef().isPresent() && !outcome.refused()) {
                    result=new RunResult.RunFinished(id,outcome.pushedRef().orElseThrow(),held.ready().changedPaths(),
                            List.of(),held.ready().tokenUsage(),false);
                } else if(outcome.refused()) {
                    result=new RunResult.RunFinished(id,null,held.ready().changedPaths(),outcome.blocked(),held.ready().tokenUsage(),false);
                } else if(outcome.failureCause().filter("PUBLICATION_CANCELLED"::equals).isPresent()) {
                    result=cancelledResult(held.execution(),held.ready());
                } else if(!end.salvaged()) {
                    // A restarted publisher may already have written remotely. Keep its claim
                    // recoverable until the actual log/exit is observed, even if cancel raced it.
                    return;
                } else {
                    result=failures.ofPublication(held.execution().execution(),held.permit(),"PUBLISHER_FAILED",
                            outcome.failureCause().orElse("Publication was not observed")+": "+outcome.failureDetail())
                            .withUsage(held.ready().tokenUsage());
                }
                if(cancelled(id) || store.revoked(held.execution()))result=cancelledResult(held.execution(),result);
            }
            store.terminal(result); // Paid usage and publication evidence are durable before any deletion or send.
            releasePublished(store.find(id).orElseThrow(),publication);
        } catch(RuntimeException failure) {
            LOG.errorf("run %s: publication observation is incomplete (%s); its claimed publisher is retained",id,failure.getClass().getSimpleName());
        } finally {
            registry.forget(id);
            leases.preserve(id);
        }
    }

    @Scheduled(every="${spire.run.work-recovery:5s}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void recover() {
        flushResults();
        if(!(runtime instanceof PublicationRuntime publication))return;
        Optional<Instant> horizon=leases.staleBefore(Duration.ofSeconds(staleAfterSeconds));
        if(horizon.isEmpty())return;
        for(var held:store.unfinished()) {
            String id=held.execution().runId();
            if(registry.isExecuting(id) || !active.add(id))continue;
            try {
                var unit=localUnit(id);
                if("publishing".equals(held.state())) {
                    if(unit.isPresent() && store.claimPublicationRecovery(id,horizon.orElseThrow()))
                        publishClaimed(store.find(id).orElseThrow(),unit.orElseThrow(),publication);
                } else if("ready".equals(held.state()) && (cancelled(id) || store.revoked(held.execution()))) {
                    unit.ifPresent(publication::cancel);
                    store.terminal(cancelledResult(held.execution(),held.ready()));
                } else if("building".equals(held.state()) && held.updatedAt().isBefore(horizon.orElseThrow())) {
                    var lease=leases.find(id);
                    if(lease.isPresent() && !lease.orElseThrow().preserved()
                            && !lease.orElseThrow().heartbeatAt().isBefore(horizon.orElseThrow()))continue;
                    // Death before the ready commit is uncertainty, never permission to rebuild.
                    // Stop any remaining compute; preserve its held workspace and report unknown usage.
                    unit.ifPresent(publication::cancel);
                    store.abandonBuild(failures.of(held.execution().execution(),RunFailureCause.SALVAGE_FAILED.name(),
                            "The worker stopped before recording build readiness; its unpublished workspace is retained"));
                }
            } catch(RuntimeException failure) {
                LOG.warnf("run %s: retained work recovery deferred (%s)",id,failure.getClass().getSimpleName());
            } finally {active.remove(id);}
        }
        for(var held:store.awaitingRelease()) {
            try {releasePublished(held,publication);}
            catch(RuntimeException failure){LOG.warnf("run %s: published workspace cleanup deferred (%s)",held.execution().runId(),failure.getClass().getSimpleName());}
        }
        flushResults();
    }

    public void flushResults() {
        for(RunResult result:store.pendingResults()) {
            if(results.report(result))store.acknowledged(result);
        }
    }

    private void releasePublished(WorkRunStore.Held held,PublicationRuntime publication) {
        if(!(held.terminal() instanceof RunResult.RunFinished finished) || finished.pushedRef()==null
                || finished.agentUnobserved())return;
        String id=held.execution().runId();
        RunHandle handle=localUnit(id).orElse(new RunHandle(id,id));
        if(publication.publicationHeld(handle))publication.destroyHeld(handle,new PublicationKey(held.execution().work().publicationKey()));
        else if(localUnit(id).isPresent())throw new IllegalStateException("Published resources no longer carry their expected hold");
        store.released(id);
        leases.release(id);
        LiveSecrets.forget(id);
        LiveSecrets.forget(id+PUBLICATION_SECRETS);
    }

    private Optional<RunHandle> localUnit(String id) {
        return runtime.discoverUnits().stream().filter(handle->handle.runId().equals(id)).findFirst();
    }

    private boolean cancelled(String id) {return registry.wasCancelled(id) || claims.taken(id,RunDispatcher.CANCEL_SLOT);}

    private boolean publicationAllowed(RunCommand.ExecuteWorkRun command) {
        try {return !cancelled(command.runId()) && !store.revoked(command);}
        catch(RuntimeException failure) {
            LOG.warnf("run %s: publication cannot read cancellation (%s); no publisher may start",command.runId(),failure.getClass().getSimpleName());
            return false;
        }
    }

    private RunResult cancelledResult(RunCommand.ExecuteWorkRun command,RunResult result) {
        // A push that already happened stays reported. Cancellation cannot undo a forge write.
        if(result instanceof RunResult.RunFinished finished && finished.pushedRef()!=null)return result;
        var usage=switch(result) {
            case RunResult.RunWorkReady ready -> ready.tokenUsage();
            case RunResult.RunFinished finished -> finished.tokenUsage();
            case RunResult.RunFailed failed -> failed.tokenUsage();
            case RunResult.RunStarted ignored -> null;
        };
        return failures.of(command.execution(),"CANCELLED","The held run was cancelled; its unpublished workspace is retained").withUsage(usage);
    }

    private final class HeldObserver implements RunObserver {
        private final RunCommand.ExecuteWorkRun command;
        HeldObserver(RunCommand.ExecuteWorkRun command){this.command=command;}
        public void event(RunEventRecord record){
            transcript.emit(record,(sent,error)->{
                if(error!=null)LOG.warnf("run %s: transcript event %d was refused by the broker (%s)",
                        record.runId(),record.sequence(),error.getClass().getSimpleName());
            });
        }
        public void unitCreated(String unitId,RunNotes notes) {
            String id=command.runId();
            RunHandle handle=new RunHandle(id,unitId);
            registry.register(id,command.execution().harness(),handle,notes);
            store.recordUnit(id,unitId);
            leases.recordUnit(id,unitId);
            results.report(new RunResult.RunStarted(id,unitId));
            if(cancelled(id) || store.revoked(command)) {registry.cancel(id);runtime.cancel(handle);}
        }
        public void unitReleased(){throw new IllegalStateException("A held build must retain its workspace");}
    }

    public void hold(RunCommand.HoldWorkRun command) {
        store.revoke(command); // Must commit before touching Docker, even if execution has not arrived.
        var held=store.find(command.runId());
        if(held.isEmpty() || !held.orElseThrow().execution().work().equals(command.work()))return;
        registry.cancel(command.runId());
        if(runtime instanceof PublicationRuntime publication)
            localUnit(command.runId()).filter(publication::publicationHeld).ifPresent(publication::cancel);
    }
}
