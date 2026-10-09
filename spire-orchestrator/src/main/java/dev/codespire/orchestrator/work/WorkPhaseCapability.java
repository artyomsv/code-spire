package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import jakarta.enterprise.context.ApplicationScoped;

/** M3 policy can request a phase; it cannot invent an executor. Slice 8 binds prepared artifacts and held builds. */
@ApplicationScoped
public class WorkPhaseCapability {
    @jakarta.inject.Inject WorkRunTransport runs;
    @jakarta.inject.Inject WorkDelivery delivery;
    @jakarta.inject.Inject WorkVerifyTransport verifies;
    public boolean available(WorkItemEvent item,String phase) {
        return switch(phase) {
            case "build" -> item.preparation()!=null && runs.available();
            // An empty command list is still available: it runs and answers no_checks_declared (M4, spec §4.2).
            case "verify" -> item.preparation()!=null && item.progress().execution()!=null && verifies.available();
            case "deliver","review" -> delivery.available(item,phase);
            default -> false;
        };
    }
}
