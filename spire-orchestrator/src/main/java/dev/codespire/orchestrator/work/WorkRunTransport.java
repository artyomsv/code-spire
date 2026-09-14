package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.RunLaunch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Slice 8b must supply a trusted publication hold before an item command can reach the worker. */
@ApplicationScoped
public class WorkRunTransport {
    @Inject RunLaunch launch;
    public boolean available() { return false; }
    public RunLaunch.Outcome dispatch(RunCommand.ExecuteRun command) {
        if(!available())throw new IllegalStateException("Item publication hold is unavailable");
        return launch.launch(command);
    }
}
