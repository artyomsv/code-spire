package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
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
 *
 * <p>The credentials arrive as the orchestrator sends them — Tink ciphertext bound to the run — and
 * are opened with a fresh keyset here, so what the containers receive is asserted on the PLAINTEXT
 * the agent and publisher would actually use, never on the opaque string that rode the bus.
 */
class RunUnitBuilderTest {

    private static final String RUN_ID = "run::github:acme/app:finding-1:1";

    private static final String SCM_LOGIN = "TEST-machine-account";

    private static final String SCM_TOKEN = "TEST-scm-token-do-not-print";

    private static final String HARNESS_KEY = "TEST-harness-key";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    private final RunUnitBuilder builder = builder(encryption);

    private static RunUnitBuilder builder(EncryptionService encryption) {
        Credentials credentials = new Credentials();
        credentials.encryption = encryption;
        credentials.mapper = JSON;
        RunUnitBuilder builder = new RunUnitBuilder();
        builder.credentials = credentials;
        builder.publisherImage = "spire-publisher:TEST";
        builder.maxWallClockSeconds = 3600;
        return builder;
    }

    @Test
    void aCommandAskingForMoreWallClockThanTheWorkerBudgetsIsRefusedBeforeAnyContainer() {
        // RunAckBudget sizes the channel's ack threshold to spire.run.max-wall-clock-seconds. A
        // command that carries more would outlive that budget — the poison-pill shape again — so it
        // is a BAD_COMMAND at the builder, where nothing has been created or paid for yet.
        RunCommand.ExecuteRun over = new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1", "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of(), 3601, packedScm(SCM_LOGIN, SCM_TOKEN), null);

        IllegalArgumentException refusal = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> builder.build(over, new CodexAdapter()));
        org.junit.jupiter.api.Assertions.assertTrue(refusal.getMessage().contains("max-wall-clock-seconds"),
                refusal.getMessage());
    }

    @Test
    void thePublisherImageComesFromConfigurationNotACompiledConstant() {
        // A registry prefix or a digest is the deployment's to choose, and both halves of the run
        // unit — agent and publisher — are configured the same way.
        RunUnitSpec unit = builder.build(command(), new CodexAdapter());
        org.junit.jupiter.api.Assertions.assertEquals("spire-publisher:TEST", unit.init().image());
        org.junit.jupiter.api.Assertions.assertEquals("spire-publisher:TEST", unit.publisher().image());
    }

    /** What the orchestrator's packer produces: the account's login and token in one envelope. */
    private String packedScm(String login, String token) {
        try {
            return encryption.encryptString(JSON.writeValueAsString(new MachineAccountCredential(login, token)),
                    RunCommand.scmCredentialAad(RUN_ID));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private RunCommand.ExecuteRun command() {
        return new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1",
                "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of("deploy/**"), 3600,
                packedScm(SCM_LOGIN, SCM_TOKEN),
                encryption.encryptString(HARNESS_KEY, RunCommand.harnessCredentialAad(RUN_ID)));
    }

    @Test
    void theMachineAccountsLoginTravelsInsideTheEnvelopeNotAsAConstant() {
        // The worker used to hardcode "spire-bot" beside every token. The login is whatever account
        // the operator registered, and it rides in the same ciphertext as the token.
        RunUnitSpec unit = unit();

        assertEquals(SCM_LOGIN, unit.init().environment().get("SPIRE_CLONE_USERNAME"));
        assertEquals(SCM_LOGIN, unit.publisher().environment().get("SPIRE_GIT_USERNAME"));
    }

    @Test
    void aBareTokenInTheEnvelopeIsRefusedNotUsedAsACredential() {
        RunCommand.ExecuteRun bare = new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1", "fix it", "codex", "gpt-5.6", "img",
                List.of(), 60, encryption.encryptString(SCM_TOKEN, RunCommand.scmCredentialAad(RUN_ID)), null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> builder.build(bare, new CodexAdapter()));
        assertFalse(refused.getMessage().contains(SCM_TOKEN), "the refusal must not quote the plaintext");
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
        assertFalse(unit.agent().environment().containsValue(SCM_TOKEN));
        assertEquals(SCM_TOKEN, unit.publisher().environment().get("SPIRE_GIT_SECRET"));
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

        assertEquals(SCM_TOKEN, unit.init().environment().get("SPIRE_CLONE_SECRET"));
        assertFalse(unit.init().environment().containsKey("SPIRE_GIT_SECRET"),
                "the token that can write must not enter the container that prepares agent-reachable disk");
    }

    @Test
    void theAgentGetsTheModelCredentialAndTheWorkspacePath() {
        RunUnitSpec unit = unit();

        assertEquals(HARNESS_KEY, unit.agent().environment().get("OPENAI_API_KEY"));
        assertTrue(unit.agent().argv().contains("/workspace"));
        assertTrue(unit.agent().argv().contains("codex"));
    }

    @Test
    void theCiphertextItselfReachesNoContainer() {
        // What rode the bus is bound to this run and useless anywhere else, but it is still a
        // credential-shaped string: the containers get the opened value or nothing.
        RunCommand.ExecuteRun command = command();
        RunUnitSpec unit = builder.build(command, new CodexAdapter());

        for (ContainerSpec container : List.of(unit.init(), unit.agent(), unit.publisher())) {
            assertFalse(container.environment().containsValue(command.scmCredential()));
            assertFalse(container.environment().containsValue(command.harnessCredential()));
        }
    }

    @Test
    void aCredentialPackedForAnotherRunIsRefused() {
        // The AAD binds the ciphertext to its run. A ciphertext lifted from one command and replayed
        // on another run's command — same workspace, same machine account — does not open.
        RunCommand.ExecuteRun other = new RunCommand.ExecuteRun("run::github:acme/app:finding-2:1",
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_2", "fix it", "codex", "gpt-5.6", "img",
                List.of(), 60, command().scmCredential(), null);

        assertThrows(RuntimeException.class, () -> builder.build(other, new CodexAdapter()));
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

    /**
     * The other half of the argv rule, which nothing asserted. The prompt was kept OFF argv and then
     * delivered nowhere: no stdin channel existed, so a STDIN arm read an empty prompt, produced
     * nothing, exited 0 and was reported finished. The agent image's entrypoint contract hands the
     * prompt from SPIRE_PROMPT to the harness on stdin, so that variable is the delivery.
     */
    @Test
    void thePromptReachesTheAgentThroughItsEntrypointContract() {
        RunUnitSpec unit = unit();

        assertEquals("fix the typo", unit.agent().environment().get("SPIRE_PROMPT"),
                "a STDIN harness receives the prompt through SPIRE_PROMPT, the entrypoint's contract");
        assertFalse(unit.publisher().environment().containsKey("SPIRE_PROMPT"),
                "the publisher never sees the work item; it has no use for it and no business with it");
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
                List.of(), 60, null, null);

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
        Credentials.Scm scm = new Credentials.Scm("spire-bot", "ghp-do-not-print", "spire-bot", "ghp-do-not-print");

        assertFalse(scm.toString().contains("ghp-do-not-print"), scm.toString());
        assertTrue(scm.toString().contains("spire-bot"));
    }
}
