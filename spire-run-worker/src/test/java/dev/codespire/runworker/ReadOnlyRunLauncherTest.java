package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-only branch of the launcher (M4 slice 3, A1): the agent's artifact comes back scrubbed, a run without
 * one fails as {@code ARTIFACT_MISSING}, and the unit is destroyed rather than retained.
 */
class ReadOnlyRunLauncherTest {

    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000003");

    static final RunCommand.ExecuteWorkDraftRun DRAFT = new RunCommand.ExecuteWorkDraftRun(
            "run::github:TEST-work/app:TEST-draft:1",
            new RunCommand.ExecuteRun("run::github:TEST-work/app:TEST-draft:1", new RepoRef("TEST-work", "app"),
                    "https://forge.example.test/TEST-work/app.git", "main", "b".repeat(40), "spire/draft-" + ATTEMPT,
                    "TEST-write the specification", "TEST-harness", "TEST-model", "TEST-image", List.of(), 60,
                    "TEST-scm", "TEST-harness-key"),
            new WorkDraftBinding("TEST-item", 1, ATTEMPT, WorkDraftBinding.Kind.SPEC, "d".repeat(64)));

    private static String artifactLine(String text) {
        return "{\"event\":\"artifact\",\"text\":\"" + text + "\"}";
    }

    private final RunLauncherTest.FakeRuntime runtime = new RunLauncherTest.FakeRuntime();

    private final RunLauncherTest.FakeAdapter adapter = new RunLauncherTest.FakeAdapter();

    private final RunLauncher launcher = RunLauncherTest.launcher(runtime, adapter);

    /** What the observer was told, in order. */
    private final List<String> observed = new ArrayList<>();

    private final RunObserver observer = new RunObserver() {
        @Override
        public void event(dev.codespire.contract.event.RunEventRecord record) {
        }

        @Override
        public void unitCreated(String unitId, RunNotes notes) {
            observed.add("created");
        }

        @Override
        public void unitReleased() {
            observed.add("released");
        }
    };

    @BeforeEach
    void fixture() {
        adapter.usage = UsageReport.of(Map.of(TokenBucket.INPUT, 7L));
        launcher.builder = new RunUnitBuilder() {
            @Override
            public RunUnitSpec build(RunCommand.ExecuteRun command, HarnessAdapter harness) {
                throw new AssertionError("a draft run must not use the pushing topology");
            }

            @Override
            public RunUnitSpec buildHeld(RunCommand.ExecuteWorkRun command, HarnessAdapter harness) {
                throw new AssertionError("a draft run must not use the held topology");
            }

            @Override
            public RunUnitSpec buildReadOnly(RunCommand.ExecuteWorkDraftRun draft, HarnessAdapter harness) {
                return null; // the fake runtime never reads it; the real topology is ReadOnlyRunUnitBuilderTest's
            }
        };
    }

    @AfterEach
    void threads() {
        launcher.stopStreams();
    }

    @Test
    void aReadOnlyRunReturnsItsArtifact() {
        runtime.publisherLines = List.of(artifactLine("TEST-specification"));

        RunResult.RunWorkArtifact artifact = assertInstanceOf(RunResult.RunWorkArtifact.class,
                launcher.launchReadOnly(DRAFT, observer));

        assertEquals("TEST-specification", artifact.text());
        assertEquals(DRAFT.draft(), artifact.draft());
        assertEquals(Map.of("INPUT", 7L), artifact.tokenUsage());
        assertFalse(artifact.repositoryChanged());
        assertTrue(artifact.activeWallSeconds() > 0);
    }

    @Test
    void theArtifactIsScrubbedOfTheRunsSecrets() {
        runtime.publisherLines = List.of(artifactLine("TEST-spec quoting " + RunLauncherTest.MODEL_KEY + " and "
                + RunLauncherTest.WRITE_SECRET));

        RunResult.RunWorkArtifact artifact = assertInstanceOf(RunResult.RunWorkArtifact.class,
                launcher.launchReadOnly(DRAFT, observer));

        assertFalse(artifact.text().contains(RunLauncherTest.MODEL_KEY), "the model key reached the artifact");
        assertFalse(artifact.text().contains(RunLauncherTest.WRITE_SECRET), "the forge secret reached the artifact");
        assertTrue(artifact.text().startsWith("TEST-spec quoting"), "the text itself must survive");
    }

    @Test
    void aCredentialSplitByAControlCharacterIsScrubbedFromTheArtifact() {
        // The publisher line's JSON escape decodes to a BEL inside the model key; stripping it later would rejoin the key.
        runtime.publisherLines = List.of(artifactLine("TEST-spec quoting "
                + RunLauncherTest.MODEL_KEY.substring(0, 10) + "\\u0007" + RunLauncherTest.MODEL_KEY.substring(10)));

        RunResult.RunWorkArtifact artifact = assertInstanceOf(RunResult.RunWorkArtifact.class,
                launcher.launchReadOnly(DRAFT, observer));

        assertTrue(artifact.text().startsWith("TEST-spec quoting"), "the text itself must survive");
        assertFalse(artifact.text().contains("\u0007"), "the control character must go before the scrub, not after it");
        assertFalse(artifact.text().contains(RunLauncherTest.MODEL_KEY), "a split model key reached the artifact");
    }

    @Test
    void aReadOnlyRunWithoutAnArtifactFails() {
        runtime.publisherLines = List.of("{\"event\":\"handoff_file_refused\",\"file\":\"artifact.md\",\"why\":\"SYMLINK\"}");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launchReadOnly(DRAFT, observer));

        assertEquals("ARTIFACT_MISSING", failed.cause());
        assertFalse(failed.retryable());
        assertEquals(Map.of("INPUT", 7L), failed.tokenUsage(), "the agent ran and was paid for");
        assertTrue(failed.detail().contains("artifact.md:SYMLINK"), "the refusal is named, not hidden");
    }

    @Test
    void aFailedAgentIsReportedAsItsOwnFailure() {
        runtime.publisherLines = List.of(artifactLine("TEST-half a specification"));
        runtime.finalization = dev.codespire.runtime.Finalization.salvaged(1, "TEST exited 1");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launchReadOnly(DRAFT, observer));

        assertEquals("HARNESS_EXIT_NONZERO", failed.cause());
        assertEquals(Map.of("INPUT", 7L), failed.tokenUsage());
    }

    @Test
    void aRepositoryChangeIsAWarningNotAFailure() {
        runtime.publisherLines = List.of("{\"event\":\"repository_changed\",\"bundles\":1}", artifactLine("TEST-plan"));

        RunResult.RunWorkArtifact artifact = assertInstanceOf(RunResult.RunWorkArtifact.class,
                launcher.launchReadOnly(DRAFT, observer));

        assertTrue(artifact.repositoryChanged());
        assertEquals("TEST-plan", artifact.text());
    }

    @Test
    void aReadOnlyRunDestroysItsUnit() {
        runtime.publisherLines = List.of(artifactLine("TEST-specification"));

        launcher.launchReadOnly(DRAFT, observer);

        assertEquals(1, runtime.destroyed.size(), "a draft unit is never retained");
        assertEquals(List.of("created", "released"), observed, "the lease is released with the unit");
    }
}
