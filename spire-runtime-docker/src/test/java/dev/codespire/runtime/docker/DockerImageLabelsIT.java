package dev.codespire.runtime.docker;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reading an agent image's labels through the run arm, against a real daemon (M3.5 part M).
 *
 * <p>The claim under test is that the factory can learn which models an image runs WITHOUT running it —
 * which is the whole reason the catalogue is a label and not a question. So this reads metadata only and
 * starts no container.
 */
class DockerImageLabelsIT {

    private static final String IMAGE = "spire-agent-codex:latest";
    private static final String MODELS_LABEL = "dev.codespire.agent.models";

    private final DockerRunRuntime runtime = new DockerRunRuntime();

    private boolean imagePresent() {
        try { runtime.client().inspectImageCmd(IMAGE).exec(); return true; }
        catch (RuntimeException absent) { return false; }
    }

    @Test
    void theReferenceImageDeclaresItsModelsInItsLabels() {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        int before = runtime.client().listContainersCmd().withShowAll(true).exec().size();

        var described = runtime.describeImage(IMAGE);
        Map<String, String> labels = described.labels();
        // A locally built image has no registry digest, so it is pinned by the daemon's own id.
        var inspected = runtime.client().inspectImageCmd(IMAGE).exec();
        assertTrue(described.pinned().equals(inspected.getId()) || described.pinned().contains("@sha256:"), described.pinned());

        assertEquals("codex", labels.get("dev.codespire.agent.harness"));
        String catalogue = labels.get(MODELS_LABEL);
        // Skip rather than fail when the image was built with plain `docker build`: that is a supported
        // way to build it, and it produces an agent that runs but declares no models.
        assumeTrue(catalogue != null && !catalogue.isBlank(),
                IMAGE + " was built without deploy/agent/build-codex.sh, so it declares no models");
        String decoded = new String(Base64.getDecoder().decode(catalogue), StandardCharsets.UTF_8);
        assertTrue(decoded.startsWith("[") && decoded.contains("\"s\":"), "a JSON array of models: " + decoded);

        assertEquals(before, runtime.client().listContainersCmd().withShowAll(true).exec().size(),
                "learning what an image runs must not run it");
    }

    /** An image that does not exist is a failure to REACH it, not an image that declares nothing. */
    @Test
    void anImageThatCannotBeReachedIsAFailureNotAnEmptyAnswer() {
        assertThrows(RuntimeException.class,
                () -> runtime.describeImage("spire-test-no-such-image-" + System.nanoTime() + ":never"));
    }
}
