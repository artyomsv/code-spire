package dev.codespire.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushGateTest {

    private ChangeSet changed(String... paths) {
        return new ChangeSet(List.of(paths).stream()
                .map(p -> new ChangedPath(p, ChangeKind.MODIFIED)).toList());
    }

    @Test
    void allowsAnOrdinaryChange() {
        PushDecision decision = PushGate.decide(changed("src/main/java/Foo.java"), List.of());

        assertTrue(decision.allowed());
        assertEquals(List.of(), decision.blocked());
    }

    @Test
    void allowsARunThatChangedNothing() {
        assertTrue(PushGate.decide(new ChangeSet(List.of()), List.of()).allowed());
    }

    @Test
    void refusesAWorkflowEditOnEveryProfile() {
        PushDecision decision = PushGate.decide(changed(".github/workflows/ci.yml"), List.of());

        assertFalse(decision.allowed(), "CI configuration is a floor no profile may lower");
        assertEquals(List.of(".github/workflows/ci.yml"), decision.blocked());
    }

    @Test
    void refusesADeletedWorkflowToo() {
        ChangeSet deleted = new ChangeSet(List.of(
                new ChangedPath(".gitlab-ci.yml", ChangeKind.DELETED)));

        assertFalse(PushGate.decide(deleted, List.of()).allowed(),
                "deleting CI changes what CI does exactly as much as editing it");
    }

    @Test
    void refusesARenameIntoAProtectedPath() {
        ChangeSet renamed = new ChangeSet(List.of(
                new ChangedPath("scripts/x.yml", ChangeKind.RENAMED_FROM),
                new ChangedPath(".github/workflows/x.yml", ChangeKind.RENAMED_TO)));

        assertFalse(PushGate.decide(renamed, List.of()).allowed());
    }

    @Test
    void refusesARenameOutOfAProtectedPathToo() {
        // Moving a workflow away deletes it, which changes what CI does. A gate that only watched
        // the target side would let the factory disable every check and then push freely.
        ChangeSet renamed = new ChangeSet(List.of(
                new ChangedPath(".github/workflows/x.yml", ChangeKind.RENAMED_FROM),
                new ChangedPath("scripts/x.yml", ChangeKind.RENAMED_TO)));

        assertFalse(PushGate.decide(renamed, List.of()).allowed());
    }

    @Test
    void theCiFloorMatchesCaseInsensitively() {
        // Different path to git, same file to a case-insensitive filesystem, and the forge runs it.
        //
        // This assertion is why PushGate does not use the JDK's glob: PathMatcher. On Windows that
        // matcher is case-insensitive natively, so this test passed there whether or not the rule
        // was enforced — vacuous on a developer machine, load-bearing only in CI. The rule is now
        // Pattern.CASE_INSENSITIVE on a plain string, so it is the same everywhere.
        assertFalse(PushGate.decide(changed(".GitHub/Workflows/ci.yml"), List.of()).allowed());
        assertFalse(PushGate.decide(changed("JENKINSFILE"), List.of()).allowed());
    }

    @Test
    void aPathGitAllowsButWindowsForbidsIsStillJudged() {
        // Path.of() raises InvalidPathException on Windows for all three of these, and every one is
        // a legal filename in a Linux repository. The first draft matched via Path.of, so the gate
        // would have thrown rather than decided — and a gate that cannot parse an input cannot
        // refuse it either.
        assertTrue(PushGate.decide(changed("weird:name.yml"), List.of()).allowed(),
                "an unusual but harmless name is judged, not crashed on");
        assertFalse(PushGate.decide(changed(".github/workflows/weird:name.yml"), List.of()).allowed(),
                "and the same name under a protected path is still refused");
        assertTrue(PushGate.decide(changed("trailing "), List.of()).allowed());
    }

    @Test
    void aBackslashSeparatedPathIsTheSameFile() {
        // Git stores forward slashes, but a hand-built ChangedPath or a Windows working tree can
        // produce backslashes, and it is the same file to the forge that runs it.
        assertFalse(PushGate.decide(changed(".github\\workflows\\ci.yml"), List.of()).allowed());
    }

    @Test
    void aNestedWorkflowIsStillUnderTheFloor() {
        assertFalse(PushGate.decide(changed(".github/workflows/sub/deep.yml"), List.of()).allowed());
    }

    @Test
    void aStarDoesNotCrossASeparator() {
        // "*" is within one segment. If it crossed separators, "deploy/*" would silently protect
        // every descendant and a profile author would never learn the difference.
        assertTrue(PushGate.decide(changed("deploy/a/b.yaml"), List.of("deploy/*")).allowed());
        assertFalse(PushGate.decide(changed("deploy/b.yaml"), List.of("deploy/*")).allowed());
    }

    @Test
    void aProfileMayProtectMore() {
        PushDecision decision = PushGate.decide(changed("deploy/values.yaml"), List.of("deploy/**"));

        assertFalse(decision.allowed());
        assertEquals(List.of("deploy/values.yaml"), decision.blocked());
    }

    @Test
    void aProfileCannotUnprotectTheFloor() {
        // An empty profile list, a permissive one, and a hostile one all behave identically.
        assertFalse(PushGate.decide(changed("Jenkinsfile"), List.of("**")).allowed());
        assertFalse(PushGate.decide(changed("Jenkinsfile"), List.of()).allowed());
    }

    @Test
    void namesEveryBlockedPathNotJustTheFirst() {
        PushDecision decision = PushGate.decide(
                changed(".github/workflows/a.yml", "src/Ok.java", "Jenkinsfile"), List.of());

        assertEquals(List.of(".github/workflows/a.yml", "Jenkinsfile"), decision.blocked());
    }

    @Test
    void namesEachBlockedPathOnlyOnce() {
        // A rename reports the same path once per side, and a run can touch one file in several
        // commits. An operator reading a refusal should see each path once.
        ChangeSet twice = new ChangeSet(List.of(
                new ChangedPath("Jenkinsfile", ChangeKind.RENAMED_FROM),
                new ChangedPath("Jenkinsfile", ChangeKind.MODIFIED)));

        assertEquals(List.of("Jenkinsfile"), PushGate.decide(twice, List.of()).blocked());
    }

    @Test
    void everyFloorEntryIsAGlobThatCompiles() {
        // Guards the guard. An entry with a typo, or one using a construct PathGlob refuses, would
        // silently protect nothing — the floor would look complete and be short by one line.
        assertEquals(ProtectedPaths.CI_FLOOR.size(),
                PathGlob.compileAll(ProtectedPaths.CI_FLOOR).size());
        assertTrue(ProtectedPaths.CI_FLOOR.size() >= 8,
                "the floor looks truncated: " + ProtectedPaths.CI_FLOOR);
    }

    @Test
    void everyFloorEntryActuallyRefusesSomething() {
        // Stronger than "it compiles": each entry must refuse a path it is meant to cover, so an
        // entry that can never match anything fails here instead of being decoration.
        for (String glob : ProtectedPaths.CI_FLOOR) {
            String sample = glob.endsWith("/**") ? glob.substring(0, glob.length() - 2) + "sample.yml" : glob;

            assertFalse(PushGate.decide(changed(sample), List.of()).allowed(),
                    "floor entry \"" + glob + "\" did not refuse \"" + sample + "\"");
        }
    }

    @Test
    void aProfileGlobUsingAnUnsupportedConstructIsRefusedLoudly() {
        // A brace or a bracket would be treated as a literal by a naive translation, so the rule
        // would compile and match nothing — a protection the operator believes they have. It is a
        // startup failure instead.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PushGate.decide(changed("src/Foo.java"), List.of("deploy/{a,b}/**")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PushGate.decide(changed("src/Foo.java"), List.of("")));
    }
}
