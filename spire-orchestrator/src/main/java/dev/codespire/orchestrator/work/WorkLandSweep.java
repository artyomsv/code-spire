package dev.codespire.orchestrator.work;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/** Lands an approved pull request (M4 land, spec §7.3). */
@ApplicationScoped
public class WorkLandSweep {
    public void drain() {}

    public void advance(UUID id) {}
}
