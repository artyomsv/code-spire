package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.RunLaunch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** RED: puts a specification or plan run on the bus (M4 slice 3). */
@ApplicationScoped
public class WorkPrepareTransport {
    @Inject RunLaunch launch;

    public boolean available() { return true; }

    public RunLaunch.Outcome dispatch(RunCommand.ExecuteWorkDraftRun command) { throw new UnsupportedOperationException("RED"); }
}
