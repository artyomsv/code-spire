package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every containment property of the design is decided by this class, so every one of them is
 * asserted here. Nothing downstream can restore a property this builder gives away.
 */
class RunUnitBuilderTest {

    private final RunUnitBuilder builder = new RunUnitBuilder();

    private RunCommand.ExecuteRun command() {
        return new RunCommand.ExecuteRun("run::github:acme/app:finding-1:1",
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1",
                "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of("deploy/**"), 3600, "enc-scm", "enc-harness");
    }

    private RunUnitSpec unit() {
        return builder.build(command(), new CodexAdapter());
    }

    private static Optional<Mount> mount(ContainerSpec container, String volume) {
        return container.mounts().stream().filter(m -> m.volume().equals(volume)).findFirst();
    }

    @Test
    void theAgentGetsNoWriteCredential() {
        RunUnitSpec unit = unit();

        // ADR-038: the agent physically cannot push, gate or no gate. Everything else in the design
        // is a second line of defence behind this one.
        assertFalse(unit.agent().environment().containsKey("SPIRE_GIT_SECRET"));
        assertFalse(unit.agent().environment().containsValue("enc-scm"));
        assertTrue(unit.publisher().environment().containsKey("SPIRE_GIT_SECRET"));
    }

    @Test
    void thePublisherNeverMountsTheWorkspace() {
        RunUnitSpec unit = unit();

        // It holds the write credential, so it must never reach agent-authored git config or hooks.
        assertTrue(mount(unit.publisher(), RunUnitBuilder.WORKSPACE).isEmpty(),
                "the publisher must not reach agent-controlled git config or hooks");
        assertTrue(mount(unit.agent(), RunUnitBuilder.WORKSPACE).isPresent());
        assertEquals(1, unit.publisher().mounts().size(),
                "handoff is the ONLY volume the publisher sees");
    }

    @Test
    void handoffIsReadOnlyToThePublisherAndWritableByTheAgent() {
        RunUnitSpec unit = unit();

        assertTrue(mount(unit.publisher(), RunUnitBuilder.HANDOFF).orElseThrow().readOnly());
        assertFalse(mount(unit.agent(), RunUnitBuilder.HANDOFF).orElseThrow().readOnly());
    }

    @Test
    void theProtectedPathsReachThePublisherAndNotTheAgent() {
        RunUnitSpec unit = unit();

        // The gate's rules come from the operator side, never from anything the agent can influence.
        // An agent that could read them could shape its work to slip past; one that could write
        // them would not need to.
        assertEquals("deploy/**", unit.publisher().environment().get("SPIRE_PROTECTED_PATHS"));
        assertFalse(unit.agent().environment().containsKey("SPIRE_PROTECTED_PATHS"));
    }

    @Test
    void theInitContainerClonesWithAReadCredentialOnly() {
        RunUnitSpec unit = unit();

        assertTrue(unit.init().environment().containsKey("SPIRE_CLONE_SECRET"));
        assertFalse(unit.init().environment().containsKey("SPIRE_GIT_SECRET"),
                "the token that can write must not enter the container that prepares agent-reachable disk");
    }

    @Test
    void theAgentGetsTheModelCredentialAndTheWorkspacePath() {
        RunUnitSpec unit = unit();

        assertEquals("enc-harness", unit.agent().environment().get("OPENAI_API_KEY"));
        assertTrue(unit.agent().argv().contains("/workspace"));
        assertTrue(unit.agent().argv().contains("codex"));
    }

    @Test
    void thePromptIsNeverInTheAgentsArgv() {
        // The work item is untrusted text and Codex reads it from stdin. If it reached argv here it
        // would be visible in docker inspect and in /proc/<pid>/cmdline, and a body beginning with
        // a hyphen would be parsed as an option.
        RunUnitSpec unit = unit();

        assertFalse(unit.agent().argv().contains("fix the typo"));
        assertEquals("-", unit.agent().argv().getLast());
    }

    @Test
    void theTwoBranchesAreCarriedSeparately() {
        // baseBranch is what the publisher CLONES; branch is what it PUSHES to. One name for both
        // would make the factory push onto the branch it forked from.
        RunUnitSpec unit = unit();

        assertEquals("main", unit.publisher().environment().get("SPIRE_BRANCH_BASE"));
        assertEquals("spire/run_1", unit.publisher().environment().get("SPIRE_BRANCH"));
    }

    @Test
    void theWallClockReachesTheUnit() {
        assertEquals(3600, unit().wallClock().toSeconds());
    }

    @Test
    void aRunWithNoScmCredentialIsRefusedRatherThanBuiltWithout() {
        RunCommand.ExecuteRun noCredential = new RunCommand.ExecuteRun("run::github:acme/app:f:1",
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc", "spire/run_1", "do it", "codex", "gpt-5.6", "img",
                List.of(), 60, null, "enc-harness");

        assertThrows(IllegalArgumentException.class,
                () -> builder.build(noCredential, new CodexAdapter()));
    }

    @Test
    void anUnknownHarnessFailsBeforeAnythingIsCreated() {
        // A run dispatched to an arm this deployment does not have must fail at the start, not
        // after a container has been created and a model call paid for.
        HarnessRegistry registry = new HarnessRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.forName("opencode"));
        assertThrows(IllegalArgumentException.class, () -> registry.forName("not-a-harness"));
        assertThrows(IllegalArgumentException.class, () -> registry.forName(" "));
        assertEquals(dev.codespire.harness.HarnessType.CODEX, registry.forName("codex").type());
    }

    @Test
    void aCredentialRecordNeverPrintsItsSecret() {
        Credentials.Scm scm = Credentials.scm("ghp-do-not-print");

        assertFalse(scm.toString().contains("ghp-do-not-print"), scm.toString());
        assertTrue(scm.toString().contains("spire-bot"));
    }
}
