package dev.codespire.runworker;

import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.ImageDescription;
import dev.codespire.runtime.RunRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model proxy image is built locally and on no registry yet, so a worker without it must not start proxying. */
class ModelProxyImageCheckTest {

    private static final String IMAGE = "TEST-model-proxy:missing";

    /** Records the images it was asked about; a missing image is a describe that throws, as a failed pull does. */
    private static final class Images extends RunLauncherTest.FakeRuntime {
        final List<String> described = new ArrayList<>();
        boolean present;

        @Override
        public ImageDescription describeImage(String image) {
            described.add(image);
            if (!present) {
                throw new IllegalStateException("TEST pull access denied for " + image);
            }
            return new ImageDescription("TEST-pinned", Map.of());
        }
    }

    private final Images images = new Images();

    private final Supplier<RunRuntime> runtime = () -> images;

    private static ModelProxyImageCheck check(CredentialProxyMode mode, EgressPolicy.Mode egress) {
        ModelProxyImageCheck check = new ModelProxyImageCheck();
        check.mode = mode;
        check.egressMode = egress;
        check.image = IMAGE;
        return check;
    }

    @Test
    void aMissingImageStopsTheLaneNamingTheVariable() {
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> check(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.REPORT).verify(runtime));

        assertTrue(refusal.getMessage().contains("SPIRE_RUN_CREDENTIAL_PROXY_IMAGE"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(IMAGE), refusal.getMessage());
        assertEquals(List.of(IMAGE), images.described);
    }

    @Test
    void aPresentImagePasses() {
        images.present = true;

        assertDoesNotThrow(() -> check(CredentialProxyMode.ALL, EgressPolicy.Mode.ENFORCE).verify(runtime));
        assertEquals(List.of(IMAGE), images.described);
    }

    @Test
    void modeOffChecksNothing() {
        assertDoesNotThrow(() -> check(CredentialProxyMode.OFF, EgressPolicy.Mode.REPORT).verify(runtime));
        assertEquals(List.of(), images.described);
    }

    /** With egress off no unit is proxied, so the image is not looked at; the startup warns instead. */
    @Test
    void egressOffChecksNothing() {
        assertDoesNotThrow(() -> check(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.OFF).verify(runtime));
        assertEquals(List.of(), images.described);
    }
}
