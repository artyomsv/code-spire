package dev.codespire.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Asking the run worker what an agent image says about itself (M3.5 part M).
 *
 * <p>The orchestrator knows which image each harness dispatches to — it holds that configuration — but
 * it cannot read an image: it has no container runtime, and on Kubernetes it never will. The run worker
 * must be able to reach every agent image or no run could start, so it is the one asked.
 *
 * <p>Rides {@code cs.harness-image-commands}, keyed by {@code requestId}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HarnessImageCommand.Describe.class, name = "Describe")
})
public sealed interface HarnessImageCommand {

    String requestId();

    /**
     * Read the model catalogue this image declares.
     *
     * @param harness the name the orchestrator dispatches under, echoed back so the answer files itself
     * @param image the exact reference the orchestrator will run — the answer describes THIS image, and a
     *     different tag of the same repository may carry a different CLI and a different list
     */
    record Describe(String requestId, String harness, String image) implements HarnessImageCommand {
        public Describe {
            if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("A request id is required");
            if (harness == null || harness.isBlank()) throw new IllegalArgumentException("A harness is required");
            if (image == null || image.isBlank()) throw new IllegalArgumentException("An image is required");
        }
    }
}
