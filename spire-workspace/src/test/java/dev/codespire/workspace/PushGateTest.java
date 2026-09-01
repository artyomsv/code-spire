package dev.codespire.workspace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        assertEquals(List.of(), decision.blockedPaths());
    }

    @Test
    void allowsARunThatChangedNothing() {
        assertTrue(PushGate.decide(new ChangeSet(List.of()), List.of()).allowed());
    }

    @Test
    void refusesAWorkflowEditOnEveryProfile() {
        PushDecision decision = PushGate.decide(changed(".github/workflows/ci.yml"), List.of());

        assertFalse(decision.allowed(), "CI configuration is a floor no profile may lower");
        assertEquals(List.of(".github/workflows/ci.yml"), decision.blockedPaths());
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
        assertEquals(List.of("deploy/values.yaml"), decision.blockedPaths());
    }

    @Test
    void namesEveryBlockedPathNotJustTheFirst() {
        PushDecision decision = PushGate.decide(
                changed(".github/workflows/a.yml", "src/Ok.java", "Jenkinsfile"), List.of());

        assertEquals(List.of(".github/workflows/a.yml", "Jenkinsfile"), decision.blockedPaths());
    }

    @Test
    void namesEachBlockedPathOnlyOnce() {
        // A rename reports the same path once per side, and a run can touch one file in several
        // commits. An operator reading a refusal should see each path once.
        ChangeSet twice = new ChangeSet(List.of(
                new ChangedPath("Jenkinsfile", ChangeKind.RENAMED_FROM),
                new ChangedPath("Jenkinsfile", ChangeKind.MODIFIED)));

        assertEquals(List.of("Jenkinsfile"), PushGate.decide(twice, List.of()).blockedPaths());
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
    void aProfileGlobUsingAnUnsupportedConstructIsRefusedLoudly() {
        // A brace or a bracket would be treated as a literal by a naive translation, so the rule
        // would compile and match nothing — a protection the operator believes they have. It is a
        // startup failure instead.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PushGate.decide(changed("src/Foo.java"), List.of("deploy/{a,b}/**")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PushGate.decide(changed("src/Foo.java"), List.of("")));
    }
    @Test
    void aLeadingDoubleStarMatchesAtTheRootAsWellAsBelowIt() {
        // "**" translated to ".*" left the following "/" as a literal, so the slash was MANDATORY:
        // "**/secrets.yml" protected every nested copy and silently missed the one at the
        // repository root. That is the failure PathGlob refuses "{" and "[" to prevent, shipped in
        // the class that refuses them — and the example profile in AUTONOMY.md was affected.
        assertFalse(PushGate.decide(changed("secrets.yml"), List.of("**/secrets.yml")).allowed(),
                "a root-level file must match a **/ glob");
        assertFalse(PushGate.decide(changed("a/b/secrets.yml"), List.of("**/secrets.yml")).allowed(),
                "and so must a nested one");
        assertTrue(PushGate.decide(changed("secrets.yaml"), List.of("**/secrets.yml")).allowed(),
                "guards the guard: **/ must not become match-everything");
    }

    @Test
    void aCompositeActionIsProtectedAtTheRootAndAtAnyDepth() {
        // A workflow saying "uses: ./tools/build" executes tools/build/action.yml, and "uses: ./"
        // executes action.yml at the root. An agent rewriting that file's run: steps never touches
        // .github/workflows at all — the workflow is unchanged and runs the new code on a runner
        // holding the repository's secrets.
        assertFalse(PushGate.decide(changed("action.yml"), List.of()).allowed());
        assertFalse(PushGate.decide(changed("action.yaml"), List.of()).allowed());
        assertFalse(PushGate.decide(changed("tools/build/action.yml"), List.of()).allowed());
        assertFalse(PushGate.decide(changed("deeply/nested/thing/action.yaml"), List.of()).allowed());
    }

    @Test
    void aSuffixedJenkinsfileIsProtectedToo() {
        // Jenkinsfile.release is a common convention, and the bare entry missed it.
        assertFalse(PushGate.decide(changed("Jenkinsfile.release"), List.of()).allowed());
        assertFalse(PushGate.decide(changed("Jenkinsfile"), List.of()).allowed());
    }

    @Test
    void aJenkinsfileBelowTheRootIsProtectedLikeACompositeAction() {
        // A multibranch or folder job points at a "Script Path", and ci/Jenkinsfile is a common
        // one. The root-only entries let it through while composite actions were covered at any
        // depth — an oversight, not a decision: the executed file IS the configuration wherever it
        // sits, which is the class the floor says it covers.
        assertFalse(PushGate.decide(changed("ci/Jenkinsfile"), List.of()).allowed());
        assertFalse(PushGate.decide(changed("deploy/Jenkinsfile.release"), List.of()).allowed());
    }

    @Test
    void bothYamlSpellingsAreProtectedWhereTheToolReadsBoth() {
        // Woodpecker reads .woodpecker.yml AND .woodpecker.yaml at the root, and Cloud Build is used
        // with both spellings; the floor listed one of each. action.yml/action.yaml already had the
        // pattern ("Both spellings: GitHub accepts each") — it was not applied consistently.
        assertFalse(PushGate.decide(changed(".woodpecker.yaml"), List.of()).allowed());
        assertFalse(PushGate.decide(changed("cloudbuild.yml"), List.of()).allowed());
        assertFalse(PushGate.decide(changed(".drone.yaml"), List.of()).allowed());
    }

    @Test
    void theFloorAppliesWhateverTheProfileSays() {
        // The version this replaces passed List.of("**") as the "hostile" profile — but "**"
        // protects EVERYTHING, so it blocked Jenkinsfile through the profile whether or not the
        // floor existed. Deleting the whole floor left it green. A profile that cannot itself
        // match Jenkinsfile is the only one that tests the floor.
        assertFalse(PushGate.decide(changed("Jenkinsfile"), List.of("docs/**")).allowed(),
                "the floor holds when the profile is about something else entirely");
        assertFalse(PushGate.decide(changed("Jenkinsfile"), List.of()).allowed(),
                "and when there is no profile at all");
    }

    @Test
    void everyFloorEntryRefusesARealPathItIsMeantToCover() {
        // The version this replaces built each sample by string surgery on the glob itself, so it
        // asserted self-consistency rather than coverage: a typo like ".github/workflow/**"
        // (singular) produced ".github/workflow/sample.yml" and passed. These paths are written out
        // by hand, so a mistyped floor entry has nothing to agree with.
        Map<String, String> covered = new LinkedHashMap<>();
        covered.put(".github/workflows/**", ".github/workflows/ci.yml");
        covered.put(".github/actions/**", ".github/actions/setup/action.yml");
        covered.put(".gitea/workflows/**", ".gitea/workflows/build.yaml");
        covered.put(".forgejo/workflows/**", ".forgejo/workflows/build.yaml");
        covered.put("action.yml", "action.yml");
        covered.put("action.yaml", "action.yaml");
        covered.put("**/action.yml", "tools/build/action.yml");
        covered.put("**/action.yaml", "tools/build/action.yaml");
        covered.put(".gitlab-ci.yml", ".gitlab-ci.yml");
        covered.put(".gitlab/**", ".gitlab/agents/x.yaml");
        covered.put("bitbucket-pipelines.yml", "bitbucket-pipelines.yml");
        covered.put("Jenkinsfile", "Jenkinsfile");
        covered.put("Jenkinsfile.*", "Jenkinsfile.release");
        covered.put("**/Jenkinsfile", "ci/Jenkinsfile");
        covered.put("**/Jenkinsfile.*", "deploy/Jenkinsfile.release");
        covered.put(".circleci/**", ".circleci/config.yml");
        covered.put("azure-pipelines.yml", "azure-pipelines.yml");
        covered.put("azure-pipelines.yaml", "azure-pipelines.yaml");
        covered.put(".drone.yml", ".drone.yml");
        covered.put(".drone.yaml", ".drone.yaml");
        covered.put(".woodpecker.yml", ".woodpecker.yml");
        covered.put(".woodpecker.yaml", ".woodpecker.yaml");
        covered.put(".woodpecker/**", ".woodpecker/build.yml");
        covered.put(".travis.yml", ".travis.yml");
        covered.put("appveyor.yml", "appveyor.yml");
        covered.put(".appveyor.yml", ".appveyor.yml");
        covered.put(".buildkite/**", ".buildkite/pipeline.yml");
        covered.put(".teamcity/**", ".teamcity/settings.kts");
        covered.put("cloudbuild.yaml", "cloudbuild.yaml");
        covered.put("cloudbuild.yml", "cloudbuild.yml");
        covered.put("buildspec.yml", "buildspec.yml");
        covered.put("buildspec.yaml", "buildspec.yaml");
        covered.put(".semaphore/**", ".semaphore/semaphore.yml");
        covered.put(".gitmodules", ".gitmodules");

        assertEquals(ProtectedPaths.CI_FLOOR.size(), covered.size(),
                "every floor entry needs a hand-written path proving it covers something real");
        assertEquals(Set.copyOf(ProtectedPaths.CI_FLOOR), covered.keySet(),
                "the floor and this table have drifted apart");

        covered.forEach((glob, path) -> assertFalse(PushGate.decide(changed(path), List.of()).allowed(),
                "floor entry \"" + glob + "\" did not refuse \"" + path + "\""));
    }

    @Test
    void aRefusalSaysWhatHappenedToEachFileNotJustWhichOnes() {
        // "ci.yml was blocked" does not tell an operator whether the factory edited that workflow
        // or deleted it, and those call for different responses.
        ChangeSet deleted = new ChangeSet(List.of(
                new ChangedPath(".github/workflows/ci.yml", ChangeKind.DELETED)));

        PushDecision decision = PushGate.decide(deleted, List.of());

        assertEquals(List.of(new ChangedPath(".github/workflows/ci.yml", ChangeKind.DELETED)),
                decision.blocked());
    }

    @Test
    void aBackslashSeparatedProfileGlobStillProtects() {
        // Normalising only the PATH and not the GLOB meant "deploy\\**" protected nothing while
        // "deploy/**" worked — and nothing said which spelling an operator had written.
        assertFalse(PushGate.decide(changed("deploy/values.yaml"), List.of("deploy\\**")).allowed());
    }
}
