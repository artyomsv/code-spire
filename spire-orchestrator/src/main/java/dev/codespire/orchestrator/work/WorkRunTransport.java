package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.RunLaunch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Prepared builds use the distinct held command; standalone runs retain their automatic publisher. */
@ApplicationScoped
public class WorkRunTransport {
    @Inject RunLaunch launch;
    public boolean available() { return true; }
    public RunLaunch.Outcome dispatch(RunCommand.ExecuteWorkRun command) {
        if(!available())throw new IllegalStateException("Item publication hold is unavailable");
        return launch.launch(command);
    }
}
