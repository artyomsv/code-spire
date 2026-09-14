package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** A publication resumes the existing run over control; it never queues another execution. */
@ApplicationScoped
public class WorkPublicationTransport {
    @Inject RunCommandEmitter emitter;
    public RunLaunch.Outcome publish(RunCommand.PublishWorkRun command) {
        try {emitter.control(command);return new RunLaunch.Dispatched();}
        catch(IllegalStateException failure) {
            return failure instanceof BrokerAckFailure ack && !ack.mayHaveLanded()
                    ?new RunLaunch.DefiniteMiss(failure):new RunLaunch.Uncertain(failure);
        }
    }
    public void cancel(String runId) {emitter.control(new RunCommand.CancelRun(runId,"Work item delivery permission is no longer current"));}
}
