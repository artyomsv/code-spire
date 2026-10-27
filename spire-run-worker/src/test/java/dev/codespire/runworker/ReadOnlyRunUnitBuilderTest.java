package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A specification or plan run's unit (M4 slice 3): the publisher reads the handoff and holds nothing it could push with. */
class ReadOnlyRunUnitBuilderTest {

    private static final String RUN_ID = "run::github:TEST-work/app:TEST-draft:1";

    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private final ObjectMapper mapper = new ObjectMapper();

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    private final RunUnitBuilder builder = new RunUnitBuilder();

    ReadOnlyRunUnitBuilderTest() {
        builder.credentials = new Credentials();
        builder.credentials.encryption = encryption;
        builder.credentials.mapper = mapper;
        builder.enterprise = RunLauncherTest.noCorporateEnvironment();
        builder.publisherImage = "TEST-publisher";
        builder.maxWallClockSeconds = 3600;
    }

    private RunCommand.ExecuteWorkDraftRun draft() throws Exception {
        String scm = encryption.encryptString(mapper.writeValueAsString(
                new MachineAccountCredential("TEST-bot", "TEST-machine-secret")), RunCommand.scmCredentialAad(RUN_ID));
        RunCommand.ExecuteRun execution = new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", "main", "b".repeat(40), "spire/draft-" + ATTEMPT,
                "TEST-write the specification", "codex", "TEST-model", "TEST-image", List.of(), 60, scm, null);
        return new RunCommand.ExecuteWorkDraftRun(RUN_ID, execution,
                new WorkDraftBinding("TEST-item", 1, ATTEMPT, WorkDraftBinding.Kind.SPEC, "d".repeat(64)));
    }

    private RunUnitSpec unit() throws Exception {
        return builder.buildReadOnly(draft(), new CodexAdapter());
    }

    @Test
    void aReadOnlyUnitHandsThePublisherNoWriteCredential() throws Exception {
        RunUnitSpec unit = unit();

        assertFalse(unit.publisher().environment().containsKey("SPIRE_GIT_SECRET"));
        assertFalse(unit.publisher().environment().containsKey("SPIRE_GIT_USERNAME"));
        assertFalse(unit.publisher().environment().containsValue("TEST-machine-secret"));
        assertFalse(unit.publisher().environment().containsKey("SPIRE_REMOTE_URI"), "it has nowhere to push to");
        assertEquals("/handoff", unit.publisher().environment().get("SPIRE_HANDOFF_DIR"));
        assertEquals(List.of(Mount.readOnly(RunUnitBuilder.HANDOFF, "/handoff")), unit.publisher().mounts());
    }

    @Test
    void aReadOnlyUnitRunsSpirePublishNone() throws Exception {
        assertEquals(List.of("spire-publish-none"), unit().publisher().argv());
    }

    @Test
    void aReadOnlyUnitMountsNoCheckpoint() throws Exception {
        RunUnitSpec unit = unit();

        assertTrue(unit.init().mounts().stream().noneMatch(mount -> mount.path().equals(RunUnitBuilder.CHECKPOINT_PATH)));
        assertFalse(unit.init().environment().containsKey("SPIRE_CHECKPOINT_HEAD"));
        // The init still clones, with the read credential; the agent gets the prompt as a build's would.
        assertEquals("TEST-machine-secret", unit.init().environment().get("SPIRE_CLONE_SECRET"));
        assertEquals("spire/draft-" + ATTEMPT, unit.init().environment().get("SPIRE_BRANCH"));
    }

    /**
     * A draft run's prompt must not carry a build's commit instruction. It told the agent to commit its work and to describe
     * what it changed, which steers it to write the specification into the work tree and leave $SPIRE_ARTIFACT empty.
     */
    @Test
    void aReadOnlyUnitsPromptAsksForTheArtifactAndNoCommit() throws Exception {
        String prompt = unit().agent().environment().get("SPIRE_PROMPT");

        assertTrue(prompt.startsWith("TEST-write the specification"), prompt);
        assertFalse(prompt.contains("Commit your work"), "a read-only run is never told to commit");
        assertFalse(prompt.contains("SPIRE_SUMMARY"), "nor to describe a change it must not make");
        assertTrue(prompt.contains("SPIRE_ARTIFACT"), "it is told where its result goes");
        assertTrue(prompt.contains("read-only"), "and that the repository is read-only");
    }
}
