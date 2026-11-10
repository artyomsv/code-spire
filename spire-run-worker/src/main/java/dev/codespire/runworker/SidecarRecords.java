package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.ModelProxyReport;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.secrets.SecretScrub;

import java.util.List;

/**
 * What a unit's sidecars saw, read together once the agent is gone and before destroy removes them: the egress proxy's
 * attempts ({@link EgressResults}) and the model proxy's report ({@link ModelProxyResults}). Neither read fails a run.
 */
record SidecarRecords(List<RunResult.EgressAttempt> egress, ModelProxyReport modelProxy) {

    static SidecarRecords read(RunRuntime runtime, RunHandle handle, SecretScrub scrub) {
        return new SidecarRecords(EgressResults.read(runtime, handle, scrub), ModelProxyResults.read(runtime, handle));
    }
}
