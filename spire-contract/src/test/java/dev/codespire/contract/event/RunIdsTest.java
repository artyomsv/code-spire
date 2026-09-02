package dev.codespire.contract.event;

import dev.codespire.contract.port.ScmType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunIdsTest {

    @Test
    void carriesThePlatformSoTwoScmsSharingAWorkspaceNameCannotCollide() {
        String onGitHub = RunIds.of(ScmType.GITHUB, "artyomsv", "spire-test", "finding-9", 1);
        String onGitLab = RunIds.of(ScmType.GITLAB, "artyomsv", "spire-test", "finding-9", 1);

        assertEquals("run::github:artyomsv/spire-test:finding-9:1", onGitHub);
        assertEquals("run::gitlab:artyomsv/spire-test:finding-9:1", onGitLab);
    }

    @Test
    void aNonCanonicalAttemptIsRefusedSoOneRunCannotTakeTwoClaims() {
        // "01" and "+1" parse to 1 but are different strings: two claim keys and two read-model
        // rows for one logical run, on the id four places call the sole idempotency mechanism.
        for (String spelling : java.util.List.of("01", "+1", "001")) {
            IllegalArgumentException refusal = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> RunIds.parse("run::github:artyomsv/spire-test:finding-9:" + spelling), spelling);
            org.junit.jupiter.api.Assertions.assertTrue(refusal.getMessage().contains("canonical"), refusal.getMessage());
        }
        assertEquals(1, RunIds.parse("run::github:artyomsv/spire-test:finding-9:1").attempt());
    }

    @Test
    void parsesBackWithoutAnInMemoryRegistry() {
        RunIds.Parsed parsed = RunIds.parse("run::github:artyomsv/spire-test:finding-9:2");

        assertEquals(ScmType.GITHUB, parsed.scmType());
        assertEquals("artyomsv", parsed.workspace());
        assertEquals("spire-test", parsed.slug());
        assertEquals("finding-9", parsed.subject());
        assertEquals(2, parsed.attempt());
    }

    @Test
    void everyPlatformRoundTrips() {
        // The spelling is ScmType.providerType(), the same string the provider registry stores, so
        // the wire form and the stored form cannot drift. A lowercased enum name would be a second
        // spelling of the same thing — and BITBUCKET_CLOUD is where the two differ.
        for (ScmType type : ScmType.values()) {
            String id = RunIds.of(type, "acme", "widgets", "issue-1", 3);

            RunIds.Parsed parsed = RunIds.parse(id);

            assertEquals(type, parsed.scmType(), id);
            assertEquals("acme", parsed.workspace(), id);
            assertEquals("widgets", parsed.slug(), id);
            assertEquals("issue-1", parsed.subject(), id);
            assertEquals(3, parsed.attempt(), id);
        }
    }

    @Test
    void aNestedGitLabNamespaceSurvivesTheRoundTrip() {
        // GitLab namespaces nest, so group/subgroup/project is a real repository. Splitting on the
        // FIRST slash — or requiring exactly two parts, which the first draft did — would refuse it
        // or silently move part of the namespace into the slug. This project has already shipped
        // two defects from assuming a flat owner/repo.
        String id = RunIds.of(ScmType.GITLAB, "group/subgroup", "project", "issue-4", 1);

        RunIds.Parsed parsed = RunIds.parse(id);

        assertEquals("group/subgroup", parsed.workspace());
        assertEquals("project", parsed.slug());
    }

    @Test
    void refusesAMalformedIdRatherThanGuessing() {
        assertThrows(IllegalArgumentException.class, () -> RunIds.parse("run::nonsense"));
        assertThrows(IllegalArgumentException.class, () -> RunIds.parse(null));
        assertThrows(IllegalArgumentException.class, () -> RunIds.parse("review::a/b#1"));
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.parse("run::mercurial:a/b:subject:1"), "unknown platform");
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.parse("run::github:noslash:subject:1"), "no repository separator");
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.parse("run::github:a/b:subject:notanumber"));
    }

    @Test
    void aBlankSubjectIsRefusedRatherThanParsedAsEmpty() {
        // split(":") drops trailing empty fields by default, so "run::github:a/b::1" would arrive
        // as four parts with a blank subject and parse cleanly into a run about nothing.
        assertThrows(IllegalArgumentException.class, () -> RunIds.parse("run::github:a/b::1"));
    }

    @Test
    void anAttemptStartsAtOneInBothDirections() {
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.of(ScmType.GITHUB, "a", "b", "s", 0));
        assertThrows(IllegalArgumentException.class, () -> RunIds.parse("run::github:a/b:s:0"));
    }

    @Test
    void aComponentThatWouldBreakTheFormatIsRefusedWhenTheIdIsBUILT() {
        // parse(of(x)) must return x. A colon anywhere in a component makes the id unparseable, and
        // a slash in the slug would move part of it into the workspace on the way back — so both
        // are refused where the id is constructed, not discovered where it is read.
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.of(ScmType.GITHUB, "a", "b", "sub:ject", 1));
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.of(ScmType.GITHUB, "a:b", "c", "s", 1));
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.of(ScmType.GITHUB, "a", "b/c", "s", 1));
        assertThrows(IllegalArgumentException.class,
                () -> RunIds.of(ScmType.GITHUB, "a", " ", "s", 1));
    }
}
