package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import dev.codespire.worksource.WorkIssueRef;
import dev.codespire.worksource.WorkSourceType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A gate stores {@code preparation.binding()} and answering it compares that stored value against a
 * RECOMPUTED one ({@code WorkGate.artifactOf}, {@code WorkItemTransitions.answer}); a build proof does
 * the same with its own recorded binding. So adding a field to the hash does not "extend" it — it
 * supersedes every open decision and invalidates every held run, silently and everywhere at once.
 *
 * <p>Hence a version. Version 1 is what M3 shipped and what every stored preparation carries, and it
 * must keep producing the same string for ever. The expected value below is built independently, from
 * the format itself, so a field added to the production method fails this test rather than changing
 * history.
 */
class WorkPreparationBindingVersionTest {

    private static WorkIssueLocation ticket(String id) {
        return new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB, "https://tracker.example.test", "TEST-project", id),
                id, URI.create("https://tracker.example.test/" + id));
    }

    private static final String SPEC_SHA = "a".repeat(64), PLAN_SHA = "b".repeat(64), COMMIT = "c".repeat(40);

    private static WorkPreparation tracker() {
        return new WorkPreparation(new WorkPreparation.Artifact(ticket("71"), SPEC_SHA),
                new WorkPreparation.Artifact(ticket("72"), PLAN_SHA),
                "main", COMMIT, "codex", "TEST-model", "TEST-operator");
    }

    private static WorkPreparation stored(UUID specId, UUID planId) {
        return new WorkPreparation(
                new WorkPreparation.Artifact(ticket("71"), SPEC_SHA, WorkPreparation.Origin.STORED, specId),
                new WorkPreparation.Artifact(ticket("72"), PLAN_SHA, WorkPreparation.Origin.STORED, planId),
                "main", COMMIT, "codex", "TEST-model", "system", WorkPreparation.STORED_BINDING);
    }

    /** The version-1 string, spelled out here rather than taken from the code it checks. */
    private static String version1String() {
        StringBuilder value = new StringBuilder();
        for (String[] artifact : new String[][] { { "71", SPEC_SHA }, { "72", PLAN_SHA } })
            for (String part : new String[] { "GITHUB", "https://tracker.example.test", "TEST-project", artifact[0], artifact[1] })
                value.append(part.length()).append(':').append(part);
        for (String part : new String[] { "main", COMMIT, "codex", "TEST-model" })
            value.append(part.length()).append(':').append(part);
        return value.toString();
    }

    @Test
    void aTrackerPreparationHashesExactlyWhatVersionOneAlwaysHashed() {
        assertEquals(WorkPreparation.digest(version1String()), tracker().binding());
    }

    @Test
    void anOlderStoredPreparationWithNoVersionStaysVersionOne() {
        WorkPreparation decoded = new WorkPreparation(new WorkPreparation.Artifact(ticket("71"), SPEC_SHA),
                new WorkPreparation.Artifact(ticket("72"), PLAN_SHA),
                "main", COMMIT, "codex", "TEST-model", "TEST-operator", 0);
        assertEquals(WorkPreparation.TRACKER_BINDING, decoded.bindingVersion());
        assertEquals(tracker().binding(), decoded.binding(), "an absent version must not change a stored hash");
    }

    @Test
    void aStoredPreparationBindsItsOwnBytes() {
        UUID specId = UUID.fromString("00000000-0000-4000-8000-000000000071");
        UUID planId = UUID.fromString("00000000-0000-4000-8000-000000000072");
        assertNotEquals(tracker().binding(), stored(specId, planId).binding(),
                "the same digests from stored bytes are not the same approval as from live tickets");
        // Re-preparing writes NEW rows, so the identity is part of what an approval binds.
        assertNotEquals(stored(specId, planId).binding(),
                stored(UUID.fromString("00000000-0000-4000-8000-0000000000aa"), planId).binding());
    }

    @Test
    void aTrackerArtifactCarriesNoStoredIdentityAndAStoredOneRequiresIt() {
        assertThrows(IllegalArgumentException.class, () -> new WorkPreparation.Artifact(ticket("71"), SPEC_SHA,
                WorkPreparation.Origin.TRACKER, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new WorkPreparation.Artifact(ticket("71"), SPEC_SHA,
                WorkPreparation.Origin.STORED, null));
    }

    /** Version 1 describes tracker tickets. A stored artifact under it would hash as if it were one. */
    @Test
    void aVersionOneBindingCannotDescribeStoredArtifacts() {
        UUID storedId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new WorkPreparation(
                new WorkPreparation.Artifact(ticket("71"), SPEC_SHA, WorkPreparation.Origin.STORED, storedId),
                new WorkPreparation.Artifact(ticket("72"), PLAN_SHA),
                "main", COMMIT, "codex", "TEST-model", "system", WorkPreparation.TRACKER_BINDING));
    }

    private static WorkPreparation atLevel(String level) {
        UUID specId = UUID.fromString("00000000-0000-4000-8000-000000000071");
        UUID planId = UUID.fromString("00000000-0000-4000-8000-000000000072");
        return new WorkPreparation(
                new WorkPreparation.Artifact(ticket("71"), SPEC_SHA, WorkPreparation.Origin.STORED, specId),
                new WorkPreparation.Artifact(ticket("72"), PLAN_SHA, WorkPreparation.Origin.STORED, planId),
                "main", COMMIT, "codex", "TEST-model", "system", WorkPreparation.EFFORT_BINDING, level);
    }

    /**
     * The level is part of what an operator approves, so two levels are two approvals. And version 3
     * with no level is still not version 2: a gate opened under 2 must not be answered by a recomputed 3.
     */
    @Test
    void theThinkingLevelIsPartOfWhatIsApproved() {
        assertNotEquals(atLevel("high").binding(), atLevel("xhigh").binding());
        assertNotEquals(atLevel("high").binding(), atLevel(null).binding());
        UUID specId = UUID.fromString("00000000-0000-4000-8000-000000000071");
        UUID planId = UUID.fromString("00000000-0000-4000-8000-000000000072");
        assertNotEquals(stored(specId, planId).binding(), atLevel(null).binding());
    }

    /** Versions 1 and 2 do not hash a level, so they may not carry one to the build. */
    @Test
    void aLevelUnderAVersionThatDoesNotHashItIsRefused() {
        UUID specId = UUID.randomUUID(), planId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new WorkPreparation(
                new WorkPreparation.Artifact(ticket("71"), SPEC_SHA, WorkPreparation.Origin.STORED, specId),
                new WorkPreparation.Artifact(ticket("72"), PLAN_SHA, WorkPreparation.Origin.STORED, planId),
                "main", COMMIT, "codex", "TEST-model", "system", WorkPreparation.STORED_BINDING, "high"));
    }

    /** The level ends up inside the harness's own config syntax; only a plain word may get there. */
    @Test
    void aLevelThatIsNotAPlainWordIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> atLevel("high\" x=\"y"));
        assertThrows(IllegalArgumentException.class, () -> atLevel("HIGH"));
        assertEquals(null, atLevel("  ").effort(), "blank is the model's own default, not a level called blank");
    }

    @Test
    void anUnknownVersionIsRefusedRatherThanHashedSomeOtherWay() {
        assertThrows(IllegalArgumentException.class, () -> new WorkPreparation(
                new WorkPreparation.Artifact(ticket("71"), SPEC_SHA), new WorkPreparation.Artifact(ticket("72"), PLAN_SHA),
                "main", COMMIT, "codex", "TEST-model", "TEST-operator", 99));
    }
}
