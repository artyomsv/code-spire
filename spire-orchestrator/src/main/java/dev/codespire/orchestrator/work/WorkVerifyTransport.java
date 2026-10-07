package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.RunCommandEmitter;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Sends a verify to the run worker on the WORK topic (M4): it takes the worker's one execution slot like a
 * build, so it queues behind a running build rather than racing it.
 */
@ApplicationScoped
public class WorkVerifyTransport {
    @Inject RunCommandEmitter emitter;

    public boolean available() { return true; }

    public RunLaunch.Outcome dispatch(RunCommand.VerifyWork command) {
        try {
            emitter.dispatch(command);
            return new RunLaunch.Dispatched();
        } catch (IllegalStateException failure) {
            return failure instanceof BrokerAckFailure ack && !ack.mayHaveLanded()
                    ? new RunLaunch.DefiniteMiss(failure) : new RunLaunch.Uncertain(failure);
        }
    }
}
