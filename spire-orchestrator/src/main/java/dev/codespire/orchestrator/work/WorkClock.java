package dev.codespire.orchestrator.work;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/** Injectable decision clock; expiry comparisons use the same instant throughout one transaction. */
@ApplicationScoped
public class WorkClock {
    public Instant now() { return Instant.now(); }
}
