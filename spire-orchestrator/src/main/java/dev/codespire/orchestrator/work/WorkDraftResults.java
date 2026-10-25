package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunResult;
import jakarta.enterprise.context.ApplicationScoped;

/** RED: the durable inbox of draft run results (M4 slice 3). */
@ApplicationScoped
public class WorkDraftResults {
    public boolean acceptsBinding(RunResult result) { return true; }

    public boolean accept(RunResult result) { return false; }

    boolean store(RunResult result) { return false; }

    public void recover() {
        // RED: nothing is recovered yet.
    }
}
