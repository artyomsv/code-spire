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
        Finalization failed = Finalization.faulted("publisher never reported an outcome");

        assertFalse(failed.salvaged(),
                "destroy must not proceed on a failed salvage — that is the loss salvage prevents");
        assertTrue(failed.detail().contains("outcome"));
    }

    @Test
    void anUnobservedRunCannotAlsoReportAnExitCode() {
        // Without this the two halves disagree: a finalization that observed nothing is
        // constructible with exit 0 and reads as a clean exit, which is exactly the claim it is
        // unable to make. True of both kinds of unobserved, not only a fault.
        assertThrows(IllegalArgumentException.class,
                () -> new Finalization(0, Finalization.Outcome.FAULTED, "salvage failed but exit 0?"));
        assertThrows(IllegalArgumentException.class,
                () -> new Finalization(0, Finalization.Outcome.OVERRAN, "overran but exit 0?"));
        assertEquals(Finalization.NOT_OBSERVED, Finalization.faulted("gone").exitCode());
        assertEquals(Finalization.NOT_OBSERVED, Finalization.overran("still running").exitCode());
    }

    @Test
    void anOverrunIsNotAFault() {
        // Both are "no exit code was observed", and an earlier version had only that one bit — so a
        // timeout and a broken daemon were the same outcome, and the timeout read as broken
        // infrastructure. They send different people to different places.
        assertFalse(Finalization.overran("wall clock").salvaged());
        assertFalse(Finalization.faulted("daemon gone").salvaged());
        assertTrue(Finalization.overran("wall clock").overran());
        assertFalse(Finalization.faulted("daemon gone").overran(),
                "a runtime that could not look must not be reported as an agent that ran too long");
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

    /**
     * A realistic unit: the agent writes the handoff, the publisher only reads it.
     *
     * <p>The first version passed the same container three times, which meant the publisher held a
     * WRITABLE mount of the agent volume — the fixture was itself an instance of the bug the
     * containment check now refuses, and it is what made that hole invisible.
     */
    private static RunUnitSpec unit(long memoryBytes, long nanoCpus, Duration wallClock) {
        ContainerSpec agent = new ContainerSpec("spire/agent:1", List.of("codex", "exec"),
                Map.of("OPENAI_API_KEY", "sk-secret"),
                List.of(Mount.writable("workspace", "/workspace"), Mount.writable("handoff", "/handoff")));
        ContainerSpec publisher = new ContainerSpec("spire/publisher:1", List.of("publish"),
                Map.of("SPIRE_GIT_SECRET", "ghp-secret"),
                List.of(Mount.readOnly("handoff", "/handoff")));
        return new RunUnitSpec("run_1", container(), agent, publisher,
                memoryBytes, nanoCpus, wallClock);
    }

    // ---- containment is enforced, not described --------------------------------------------

    @Test
    void thePublisherMayNotWriteToAVolumeTheAgentCanWrite() {
        // The invariant ADR-039 rests on. Mount made the read-only flag typed so it could not be
        // misspelled — but nothing READ it, so this unit compiled and ran, handing the process
        // that holds the git write credential a volume the agent can write to.
        ContainerSpec agent = new ContainerSpec("img", List.of(), Map.of(),
                List.of(Mount.writable("handoff", "/handoff")));
        ContainerSpec badPublisher = new ContainerSpec("img", List.of(), Map.of(),
                List.of(Mount.writable("handoff", "/handoff")));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new RunUnitSpec("run_1", container(), agent, badPublisher,
                        1024, 1024, Duration.ofMinutes(1)));
        assertTrue(refused.getMessage().contains("handoff"), refused.getMessage());
    }

    @Test
    void theSameVolumeReadOnlyOnThePublisherIsTheWholePoint() {
        // Guards the guard: a check that refused every shared volume would also pass the test
        // above, and would forbid the handoff the design is built on.
        ContainerSpec agent = new ContainerSpec("img", List.of(), Map.of(),
                List.of(Mount.writable("handoff", "/handoff")));
        ContainerSpec publisher = new ContainerSpec("img", List.of(), Map.of(),
                List.of(Mount.readOnly("handoff", "/handoff")));

        RunUnitSpec spec = new RunUnitSpec("run_1", container(), agent, publisher,
                1024, 1024, Duration.ofMinutes(1));

        assertTrue(spec.publisher().mounts().getFirst().readOnly());
    }

    @Test
    void aBlankRunIdIsRefused() {
        // Every container and volume is labelled with it, so a blank one is undiscoverable: the
        // watchdog cannot attribute the unit and destroy targets nothing.
        assertThrows(IllegalArgumentException.class, () -> new RunUnitSpec(" ", container(),
                container(), container(), 1024, 1024, Duration.ofMinutes(1)));
    }

    @Test
    void aContainerCannotMountTwoThingsAtOnePath() {
        // A List permits duplicates, which re-opens the hole the typed read-only flag closed: an
        // arm that dedups last-wins would end up with a writable /handoff and no error anywhere.
        assertThrows(IllegalArgumentException.class, () -> new ContainerSpec("img", List.of(),
                Map.of(), List.of(Mount.readOnly("ho", "/handoff"), Mount.writable("x", "/handoff"))));
    }

    @Test
    void aSalvagedRunCannotClaimItObservedNothing() {
        // The sentinel has to be guarded on both sides or it is ambiguous, and Docker reports
        // State.ExitCode as -1 for a container that never started — a real path that arrives here
        // as a salvaged unit.
        assertThrows(IllegalArgumentException.class,
                () -> Finalization.salvaged(Finalization.NOT_OBSERVED, "exit -1?"));
    }
}