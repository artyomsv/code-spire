package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * Dispatches a run onto {@code cs.run-commands}, keyed by runId, and waits for the broker's ack.
 *
 * <p>The wait is the point. {@code POST /api/runs} answers 201 only after this returns, so a lost
 * dispatch surfaces as a 5xx rather than as an accepted run that never starts — the gateway makes
 * the same choice before its 202, and for the same reason: a silent non-start is indistinguishable
 * from a run still queued.
 */
@ApplicationScoped
public class RunCommandEmitter {

    @Inject
    @Channel("run-commands-out")
    Emitter<RunCommand> commands;

    public void dispatch(RunCommand command) {
        KafkaSends.sendAndAwait(commands, command.runId(), command,
                "run command " + command.getClass().getSimpleName() + " for " + command.runId());
    }
}
