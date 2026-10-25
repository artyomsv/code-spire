package dev.codespire.orchestrator.work;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether generated preparation is switched on (M4 slice 3, decision 1: dark until wired).
 *
 * <p>The one place every part of the generated path asks, so the build setup cannot offer what admission
 * would ignore. While it is off, the setup offers only {@code composed} and refuses {@code generated}.
 * Slice 11 deletes it, and the repository setting alone decides.
 */
@ApplicationScoped
public class GeneratedPreparation {
    @ConfigProperty(name = "spire.work.generated-preparation", defaultValue = "false")
    boolean enabled;

    public boolean enabled() { return enabled; }
}
