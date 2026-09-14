package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import jakarta.enterprise.context.ApplicationScoped;

/** M3 policy can request a phase; it cannot invent an executor. Slice 8 binds prepared artifacts and held builds. */
@ApplicationScoped
public class WorkPhaseCapability {
    @jakarta.inject.Inject WorkRunTransport runs;
    @jakarta.inject.Inject WorkDelivery delivery;
    public boolean available(WorkItemEvent item,String phase) {
        return switch(phase) {
            case "build" -> item.preparation()!=null && runs.available();
            case "deliver","review" -> delivery.available(item,phase);
            default -> false;
        };
    }
}
