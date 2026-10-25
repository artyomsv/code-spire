package dev.codespire.orchestrator.work;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** RED: dispatches the pending specification and plan runs (M4 slice 3). */
@ApplicationScoped
public class WorkPrepareDispatcher {
    @Inject WorkPrepareTransport transport;

    public void drain() {
        // RED: nothing is dispatched yet.
    }
}
