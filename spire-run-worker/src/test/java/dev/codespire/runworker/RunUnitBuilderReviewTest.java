package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.review.ReviewRunBinding;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A harness review run's unit (harness reviews, step 4): the read-only topology a draft has, with the review answer's
 * larger artifact cap handed to the publisher, and a draft's unit left as it was.
 */
class RunUnitBuilderReviewTest {

    private static final String RUN_ID = "run::github:TEST-work/app:review-7-aaaaaaaaaaaa-0123abcd:1";

    private static final String DRAFT_RUN_ID = "run::github:TEST-work/app:TEST-draft:1";

    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000005");

    private final ObjectMapper mapper = new ObjectMapper();

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    private final RunUnitBuilder builder = new RunUnitBuilder();

    RunUnitBuilderReviewTest() {
        builder.credentials = new Credentials();
        builder.credentials.encryption = encryption;
        builder.credentials.mapper = mapper;
        builder.enterprise = RunLauncherTest.noCorporateEnvironment();
        builder.publisherImage = "TEST-publisher";
        builder.maxWallClockSeconds = 3600;
        builder.egressMode = dev.codespire.runtime.EgressPolicy.Mode.OFF;
    }

    private RunCommand.ExecuteRun execution(String runId, String branch) throws Exception {
        String scm = encryption.encryptString(mapper.writeValueAsString(
                new MachineAccountCredential("TEST-bot", "TEST-reviewer-secret")), RunCommand.scmCredentialAad(runId));
        return new RunCommand.ExecuteRun(runId, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", branch, "a".repeat(40), branch,
                "TEST-review the pull request", "codex", "TEST-model", "TEST-image", List.of(), 60, scm, null);
    }

    private RunUnitSpec reviewUnit() throws Exception {
        RunCommand.ExecuteReviewRun review = new RunCommand.ExecuteReviewRun(RUN_ID, execution(RUN_ID, "TEST-source"),
                new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1));
        return builder.buildReview(review, new CodexAdapter());
    }

    private RunUnitSpec draftUnit() throws Exception {
        RunCommand.ExecuteWorkDraftRun draft = new RunCommand.ExecuteWorkDraftRun(DRAFT_RUN_ID,
                execution(DRAFT_RUN_ID, "spire/draft-" + ATTEMPT),
                new WorkDraftBinding("TEST-item", 1, ATTEMPT, WorkDraftBinding.Kind.SPEC, "d".repeat(64)));
        return builder.buildReadOnly(draft, new CodexAdapter());
    }

    @Test
    void aReviewUnitIsReadOnlyAndCarriesTheReviewCap() throws Exception {
        RunUnitSpec unit = reviewUnit();

        assertEquals(List.of("spire-publish-none"), unit.publisher().argv());
        assertEquals("262144", unit.publisher().environment().get("SPIRE_ARTIFACT_MAX_BYTES"));
        assertFalse(unit.publisher().environment().containsKey("SPIRE_GIT_SECRET"), "a review pushes nothing");
        assertFalse(unit.publisher().environment().containsValue("TEST-reviewer-secret"));
        assertEquals(List.of(Mount.readOnly(RunUnitBuilder.HANDOFF, "/handoff")), unit.publisher().mounts());
        assertEquals("TEST-reviewer-secret", unit.init().environment().get("SPIRE_CLONE_SECRET"),
                "init clones the head with the reviewer's credential; the agent never sees it");
        assertFalse(unit.agent().environment().containsValue("TEST-reviewer-secret"));
    }

    /** A draft keeps its 32 KiB: the larger cap is the review's, never every read-only unit's. */
    @Test
    void aDraftUnitKeepsItsOwnCap() throws Exception {
        String draftCap = draftUnit().publisher().environment().get("SPIRE_ARTIFACT_MAX_BYTES");

        assertNotEquals("262144", draftCap, "32768 or no cap at all, which the publisher reads as 32768");
        assertEquals("262144", reviewUnit().publisher().environment().get("SPIRE_ARTIFACT_MAX_BYTES"));
    }
}
