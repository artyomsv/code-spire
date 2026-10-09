package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Mount;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RunUnitBuilderVerifyTest {
    final ObjectMapper mapper = new ObjectMapper();
    final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());
    final WorkRunBinding binding = new WorkRunBinding("TEST-work", 1, UUID.randomUUID(), "a".repeat(64));
    final RunUnitBuilder builder = new RunUnitBuilder();
    final UUID attempt = UUID.randomUUID();
    final String head = "c".repeat(40);

    RunUnitBuilderVerifyTest() {
        builder.credentials = new Credentials();
        builder.credentials.encryption = encryption;
        builder.credentials.mapper = mapper;
        builder.enterprise = RunLauncherTest.noCorporateEnvironment();
        builder.publisherImage = "TEST-publisher";
        builder.maxWallClockSeconds = 1800;
    }

    String scm(String id) throws Exception {
        return encryption.encryptString(mapper.writeValueAsString(new MachineAccountCredential("TEST-bot", "TEST-read-secret")), RunCommand.scmCredentialAad(id));
    }
    RunCommand.ExecuteRun execution(String id) throws Exception {
        return new RunCommand.ExecuteRun(id, new RepoRef("TEST-work", "app"), "https://forge.example.test/TEST/app.git", "main", "b".repeat(40),
                "spire/TEST-build", "TEST task", "codex", "TEST-model", "TEST-agent-image", List.of(), 60, scm(id), null);
    }
    RunCommand.ExecuteWorkRun held() throws Exception { return new RunCommand.ExecuteWorkRun(execution("TEST-run"), binding); }
    RunCommand.VerifyWork verify(long timeout, String... commands) {
        return new RunCommand.VerifyWork("TEST-run", binding, attempt, head, List.of(commands), timeout);
    }

    @Test void prepareRebuildsTheCheckpointWithTheReadCredential() throws Exception {
        var unit = builder.verifyUnit(held(), verify(600, "make test"));
        ContainerSpec prepare = unit.prepare();
        assertEquals("TEST-publisher", prepare.image());
        assertEquals(List.of("spire-verify-prepare"), prepare.argv());
        assertEquals(head, prepare.environment().get("SPIRE_CHECKPOINT_HEAD"));
        assertEquals("TEST-read-secret", prepare.environment().get("SPIRE_CLONE_SECRET"));
        assertEquals("/handoff", prepare.environment().get("SPIRE_HANDOFF_DIR"));
        assertEquals(List.of(Mount.readOnly("handoff", "/handoff"), Mount.writable("verify-" + attempt, "/workspace")), prepare.mounts());
        assertEquals(Duration.ofSeconds(600), unit.timeout());
    }

    @Test void eachCheckRunsTheCommandInTheAgentImageWithNoCredential() throws Exception {
        var unit = builder.verifyUnit(held(), verify(600, "./gradlew check", "npm test"));
        assertEquals(2, unit.checks().size());
        ContainerSpec check = unit.checks().getFirst();
        assertEquals("TEST-agent-image", check.image());
        assertEquals(List.of("/bin/sh", "-c"), check.entrypoint());
        assertEquals(List.of("./gradlew check"), check.argv());
        assertEquals(java.util.Map.of(), check.environment());
        assertEquals(List.of(Mount.writable("verify-" + attempt, "/workspace")), check.mounts());
    }

    @Test void aTimeLimitOverTheWorkersMaximumIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> builder.verifyUnit(held(), verify(1801, "make")));
    }

    @Test void aVerifyOfAnotherBuildIsRefused() {
        var other = new RunCommand.VerifyWork("TEST-run", new WorkRunBinding("TEST-other", 1, UUID.randomUUID(), "a".repeat(64)), attempt, head, List.of("make"), 60);
        assertThrows(IllegalArgumentException.class, () -> builder.verifyUnit(held(), other));
    }

    @Test void aRetriedBuildsInitReadsThePreviousRunsHandoffReadOnly() throws Exception {
        var retried = new RunCommand.ExecuteWorkRun(execution("TEST-retry").fromCheckpoint("TEST-previous", head), binding);
        var init = builder.buildHeld(retried, new CodexAdapter()).init();
        assertTrue(init.mounts().contains(Mount.ofRun("TEST-previous", "handoff", "/checkpoint")));
        assertEquals(head, init.environment().get("SPIRE_CHECKPOINT_HEAD"));
        assertEquals("/checkpoint", init.environment().get("SPIRE_CHECKPOINT_DIR"));
    }

    @Test void aFirstBuildsInitHasNoCheckpoint() throws Exception {
        var init = builder.buildHeld(held(), new CodexAdapter()).init();
        assertEquals(List.of(Mount.writable("workspace", "/workspace")), init.mounts());
        assertNull(init.environment().get("SPIRE_CHECKPOINT_HEAD"));
    }
}
