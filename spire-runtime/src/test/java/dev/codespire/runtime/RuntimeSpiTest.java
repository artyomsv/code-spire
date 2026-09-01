package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSpiTest {

    private static ContainerSpec container() {
        return new ContainerSpec("spire/agent:1", List.of("codex", "exec"),
                Map.of("OPENAI_API_KEY", "sk-secret"),
                List.of(Mount.writable("workspace", "/workspace")));
    }

    // ---- salvage is not teardown ------------------------------------------------------------

    @Test
    void aFailedSalvageIsNotASuccessfulRun() {
        Finalization failed = Finalization.salvageFailed("publisher never reported an outcome");

        assertFalse(failed.salvaged(),
                "destroy must not proceed on a failed salvage — that is the loss salvage prevents");
        assertTrue(failed.detail().contains("outcome"));
    }

    @Test
    void aFailedSalvageCannotAlsoReportAnExitCode() {
        // Without this the two halves disagree: new Finalization(0, false, ...) is constructible
        // and reads as a clean exit that was never observed, which is exactly the claim a failed
        // salvage is unable to make.
        assertThrows(IllegalArgumentException.class,
                () -> new Finalization(0, false, "salvage failed but exit 0?"));
        assertEquals(Finalization.NOT_OBSERVED, Finalization.salvageFailed("gone").exitCode());
    }

    @Test
    void aSalvageMustSayWhatHappened() {
        // "read the logs" is not an outcome. A blank detail on a preserved unit leaves an operator
        // with a container to inspect and no reason it was kept.
        assertThrows(IllegalArgumentException.class, () -> Finalization.salvaged(0, "  "));
    }

    @Test
    void aSuccessfulSalvageKeepsTheProcessExitCode() {
        assertEquals(137, Finalization.salvaged(137, "OOM-killed, workspace archived").exitCode());
    }

    // ---- capabilities are declared, not assumed ----------------------------------------------

    @Test
    void capabilitiesDeclareNativeSidecarSupportRatherThanAssumingIt() {
        // Kubernetes >= 1.29 terminates a sidecar when the main container exits. Below that, the
        // publisher needs an explicit sentinel file to know the agent finished (RUN-TOPOLOGY §3) —
        // otherwise nothing ever stops it.
        RuntimeCapabilities modern = new RuntimeCapabilities(true, true, false, true, true, true);
        RuntimeCapabilities old = new RuntimeCapabilities(true, true, false, true, true, false);

        assertTrue(modern.nativeSidecar());
        assertFalse(old.nativeSidecar());
    }

    // ---- a mount's read-only flag is typed, not a string suffix ------------------------------

    @Test
    void aReadOnlyMountSaysSoInItsType() {
        // The first draft carried this as a ":ro" suffix on a path string. It is the mechanism that
        // lets /handoff reach the publisher while the publisher can write to no shared volume, so a
        // dropped or misspelled suffix silently grants write access with nothing to notice.
        assertTrue(Mount.readOnly("handoff", "/handoff").readOnly());
        assertFalse(Mount.writable("workspace", "/workspace").readOnly());
    }

    @Test
    void aMountPathMustBeAbsolute() {
        // A relative mount path resolves against the image's WORKDIR, which the run unit does not
        // control — so the same spec would mount somewhere different per image.
        assertThrows(IllegalArgumentException.class, () -> Mount.readOnly("handoff", "handoff"));
        assertThrows(IllegalArgumentException.class, () -> Mount.readOnly("", "/handoff"));
    }

    // ---- credentials never reach a log line --------------------------------------------------

    @Test
    void aContainerSpecNeverPrintsAnEnvironmentValue() {
        // A record prints every component, and log.info("creating {}", spec) is the obvious line to
        // write. Names are useful diagnostics; values are the credential.
        String rendered = container().toString();

        assertFalse(rendered.contains("sk-secret"), rendered);
        assertTrue(rendered.contains("OPENAI_API_KEY"));
        assertTrue(rendered.contains("spire/agent:1"));
    }

    @Test
    void aContainerSpecSnapshotsEverythingHandedToIt() {
        List<String> argv = new ArrayList<>(List.of("codex"));
        Map<String, String> env = new HashMap<>(Map.of("A", "1"));
        List<Mount> mounts = new ArrayList<>(List.of(Mount.writable("w", "/w")));

        ContainerSpec spec = new ContainerSpec("img", argv, env, mounts);
        argv.add("injected");
        env.put("B", "2");
        mounts.add(Mount.writable("x", "/x"));

        assertEquals(1, spec.argv().size(), "argv must be a snapshot — it becomes a command line");
        assertEquals(1, spec.environment().size());
        assertEquals(1, spec.mounts().size());
    }

    @Test
    void aContainerMustNameAnImage() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerSpec(" ", List.of(), Map.of(), List.of()));
    }

    // ---- a run unit has real bounds ----------------------------------------------------------

    @Test
    void aRunUnitRefusesToBeUnbounded() {
        // Unlimited is not a limit. An agent that can allocate without a ceiling takes the host
        // down with it, and every other run on that host.
        assertThrows(IllegalArgumentException.class, () -> unit(0, 1, Duration.ofMinutes(30)));
        assertThrows(IllegalArgumentException.class, () -> unit(1, 0, Duration.ofMinutes(30)));
        assertThrows(IllegalArgumentException.class, () -> unit(1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> unit(1, 1, Duration.ofMinutes(-1)));
    }

    @Test
    void anOrdinaryRunUnitIsAccepted() {
        // Guards the guard: a validator that refused everything would pass every case above.
        RunUnitSpec spec = unit(2L << 30, 2_000_000_000L, Duration.ofMinutes(30));

        assertEquals("run_1", spec.runId());
        assertEquals(Duration.ofMinutes(30), spec.wallClock());
    }

    private static RunUnitSpec unit(long memoryBytes, long nanoCpus, Duration wallClock) {
        return new RunUnitSpec("run_1", container(), container(), container(),
                memoryBytes, nanoCpus, wallClock);
    }

    // ---- the two log streams are distinguishable ---------------------------------------------

    @Test
    void theTwoLogStreamsAreNamedApart() {
        // AGENT is untrusted text the model can write; PUBLISHER is the gate's audit trail.
        // Conflating them would let the agent forge a line that reads like a gate decision.
        assertEquals(2, LogChannel.values().length);
        assertFalse(LogChannel.AGENT == LogChannel.PUBLISHER);
    }
}
