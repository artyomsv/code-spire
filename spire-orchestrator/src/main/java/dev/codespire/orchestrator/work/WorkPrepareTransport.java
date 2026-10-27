package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.RunLaunch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Puts a specification or plan run on the bus (M4 slice 3). The boundary the dispatcher's claim precedes, kept apart from
 * {@link WorkRunTransport} so a draft command can never be launched as a held build.
 */
@ApplicationScoped
public class WorkPrepareTransport {
    @Inject RunLaunch launch;

    public RunLaunch.Outcome dispatch(RunCommand.ExecuteWorkDraftRun command) {
        return launch.launch(command);
    }
}
