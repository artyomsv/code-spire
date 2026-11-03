package dev.codespire.runworker;

import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.UnitInputs;

import java.util.ArrayList;
import java.util.List;

/**
 * A review unit's inputs volume (harness reviews, step 6): init mounts it writable, the agent read-only at
 * {@code /inputs}, and the publisher not at all. Every review unit gets it, {@code NONE} included as an empty volume,
 * so every review unit has one shape. {@link RunUnitSpec} refuses any other wiring.
 */
final class ReviewUnitInputs {

    static final String VOLUME = "inputs";

    static final String PATH = "/inputs";

    private ReviewUnitInputs() {
    }

    static RunUnitSpec withInputs(RunUnitSpec unit, HarnessReviewInputs inputs) {
        return unit.withInit(withMount(unit.init(), Mount.writable(VOLUME, PATH)))
                .withAgent(withMount(unit.agent(), Mount.readOnly(VOLUME, PATH)))
                .withInputs(new UnitInputs(VOLUME, PATH, inputs.asMap()));
    }

    /** The same container with one more mount, its entrypoint kept, as {@code RunUnitBuilder.withVariable} does. */
    private static ContainerSpec withMount(ContainerSpec container, Mount mount) {
        List<Mount> mounts = new ArrayList<>(container.mounts());
        mounts.add(mount);
        return new ContainerSpec(container.image(), container.argv(), container.environment(), mounts,
                container.entrypoint());
    }
}
