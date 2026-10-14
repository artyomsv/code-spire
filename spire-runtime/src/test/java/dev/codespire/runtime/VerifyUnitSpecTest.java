package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VerifyUnitSpecTest {
    final UUID attempt = UUID.randomUUID();
    final String volume = VerifyUnitSpec.VOLUME_PREFIX + attempt;
    static final List<String> SHELL = List.of("/bin/sh", "-c");

    ContainerSpec prepare() {
        return new ContainerSpec("TEST-publisher", List.of("spire-verify-prepare"), Map.of("SPIRE_CLONE_SECRET", "TEST"),
                List.of(Mount.readOnly("handoff", "/handoff"), Mount.writable(volume, "/workspace")));
    }
    ContainerSpec check(Map<String, String> env, List<Mount> mounts, List<String> entrypoint) {
        return new ContainerSpec("TEST-agent", List.of("make"), env, mounts, entrypoint);
    }
    VerifyUnitSpec spec(ContainerSpec prepare, List<ContainerSpec> checks) {
        return new VerifyUnitSpec("TEST-run", attempt, prepare, checks, EnterpriseEnvironment.NONE, 1, 1, 1, Duration.ofSeconds(60));
    }

    @Test void aContainedCheckIsAccepted() {
        var unit = spec(prepare(), List.of(check(Map.of("TEST_LEVEL", "1"), List.of(Mount.writable(volume, "/workspace")), SHELL)));
        assertEquals(volume, unit.volume());
    }
    @Test void checksMayNotSeeTheHandoff() {
        var leaky = check(Map.of(), List.of(Mount.writable(volume, "/workspace"), Mount.readOnly("handoff", "/handoff")), SHELL);
        assertThrows(IllegalArgumentException.class, () -> spec(prepare(), List.of(leaky)));
    }
    @Test void checksWriteOnlyTheVerifyVolume() {
        var agentWorkspace = check(Map.of(), List.of(Mount.writable("workspace", "/workspace")), SHELL);
        assertThrows(IllegalArgumentException.class, () -> spec(prepare(), List.of(agentWorkspace)));
    }
    @Test void aCheckCarriesNoCredentialVariable() {
        for (String name : List.of("SPIRE_CLONE_SECRET", "OPENAI_API_KEY", "GH_TOKEN", "DB_PASSWORD", "TEST_CREDENTIAL", "spire_anything")) {
            var check = check(Map.of(name, "TEST"), List.of(Mount.writable(volume, "/workspace")), SHELL);
            assertThrows(IllegalArgumentException.class, () -> spec(prepare(), List.of(check)), name);
        }
    }
    @Test void aCheckOverridesTheAgentEntrypoint() {
        var wrapped = check(Map.of(), List.of(Mount.writable(volume, "/workspace")), null);
        assertThrows(IllegalArgumentException.class, () -> spec(prepare(), List.of(wrapped)));
    }
    @Test void prepareReadsOnlyItsOwnHandoffReadOnly() {
        var writableHandoff = new ContainerSpec("TEST-publisher", List.of("spire-verify-prepare"), Map.of(),
                List.of(Mount.writable("handoff", "/handoff"), Mount.writable(volume, "/workspace")));
        assertThrows(IllegalArgumentException.class, () -> spec(writableHandoff, List.of()));
    }
    @Test void aForeignRunMountIsReadOnly() {
        assertThrows(IllegalArgumentException.class, () -> new Mount("handoff", "/checkpoint", false, "TEST-previous"));
        assertTrue(Mount.ofRun("TEST-previous", "handoff", "/checkpoint").readOnly());
        assertEquals("TEST-previous", Mount.ofRun("TEST-previous", "handoff", "/checkpoint").run());
    }
    @Test void anEmptyEntrypointOverrideIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerSpec("TEST", List.of(), Map.of(), List.of(), List.of()));
        assertNull(new ContainerSpec("TEST", List.of(), Map.of(), List.of()).entrypoint());
    }
}
