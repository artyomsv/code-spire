package dev.codespire.orchestrator.work;

import dev.codespire.orchestrator.factory.FactoryConfig;
import jakarta.ws.rs.core.Response;

/**
 * Test-only access to this package's doors from a factory test (harness reviews 3b), like
 * {@code WorkRunTestConfiguration} in the other direction: the refusal names an item shows and the preparation
 * resource built by hand, with only the collaborators a door reaches.
 */
public final class WorkTestDoors {
    private WorkTestDoors() {}

    /** The reason a work item is stopped with for this assembly failure. */
    public static String reasonOf(RuntimeException failure) {
        return WorkRunAssemblyRefusals.reasonOf(failure);
    }

    /** The preparation options of a deployment configured as given. */
    public static WorkPreparationResource.Options preparationOptions(FactoryConfig config) {
        WorkPreparationResource resource = new WorkPreparationResource();
        resource.factoryConfig = config;
        return resource.options();
    }

    /**
     * A hand registration with no identity, store, sources or transitions: a guard that runs before them answers, and
     * anything later is refused as an unidentified operator.
     */
    public static Response register(String id, WorkPreparationResource.Input input) {
        return new WorkPreparationResource().register(id, input);
    }
}
