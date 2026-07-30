package dev.codespire.context.github;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar, with no network. Extraction favours recall — a false candidate costs one 404 that the
 * provider skips — so these tests pin the boundaries that matter: what must NOT match (so real prose
 * does not turn into a fetch storm), and what must parse into which shape (so a repo-relative
 * reference is distinguishable from one that names its own repository).
 */
class GitHubIssueRefsTest {

    @Test
    void findsABareReferenceAfterWhitespaceOrPunctuation() {
        assertEquals(Set.of("#123"), GitHubIssueRefs.candidates("fixes #123"));
        assertEquals(Set.of("#7"), GitHubIssueRefs.candidates("(#7)"));
    }

    /** A '#' glued to a word is a fragment or an anchor, not an issue. */
    @Test
    void ignoresAHashInsideAWord() {
        assertTrue(GitHubIssueRefs.candidates("abc#1").isEmpty());
        assertTrue(GitHubIssueRefs.candidates("http://x/y#3").isEmpty());
    }

    /** The qualified form must win outright: it must not also yield a bare '#123'. */
    @Test
    void findsAQualifiedReferenceWithoutAlsoYieldingTheBareForm() {
        assertEquals(Set.of("acme/widgets#123"), GitHubIssueRefs.candidates("see acme/widgets#123"));
    }

    @Test
    void findsIssueAndPullRequestUrlsIncludingOnAnEnterpriseHost() {
        assertEquals(Set.of("https://github.com/acme/widgets/issues/12"),
                GitHubIssueRefs.candidates("https://github.com/acme/widgets/issues/12"));
        assertEquals(Set.of("https://ghe.example.invalid/acme/widgets/pull/34"),
                GitHubIssueRefs.candidates("https://ghe.example.invalid/acme/widgets/pull/34"));
    }

    @Test
    void capsCandidatesSoALinkFarmCannotDriveAFetchStorm() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            text.append(" #").append(i);
        }
        assertEquals(10, GitHubIssueRefs.candidates(text.toString()).size());
    }

    @Test
    void parsesABareReferenceAsRepoRelative() {
        GitHubIssueRefs.Ref ref = GitHubIssueRefs.parse("#123").orElseThrow();
        assertTrue(ref.isRepoRelative());
        assertEquals(123, ref.number());
    }

    @Test
    void parsesAQualifiedReferenceAndAUrlIntoTheirOwnRepository() {
        GitHubIssueRefs.Ref qualified = GitHubIssueRefs.parse("acme/widgets#123").orElseThrow();
        assertFalse(qualified.isRepoRelative());
        assertEquals("acme", qualified.owner());
        assertEquals("widgets", qualified.repo());
        assertEquals(123, qualified.number());

        GitHubIssueRefs.Ref url =
                GitHubIssueRefs.parse("https://github.com/acme/widgets/issues/9").orElseThrow();
        assertEquals("acme", url.owner());
        assertEquals("widgets", url.repo());
        assertEquals(9, url.number());
    }

    @Test
    void parsesNothingFromAForeignReference() {
        assertEquals(Optional.empty(), GitHubIssueRefs.parse("PROJ-123"));
        assertEquals(Optional.empty(), GitHubIssueRefs.parse("https://acme.atlassian.net/browse/CS-1"));
    }

    /** Two spellings of one repository must dedupe across retrieval rounds. */
    @Test
    void normalizesCaseSoOneIssueIsNotFetchedTwice() {
        assertEquals(GitHubIssueRefs.normalize("acme/widgets#12"),
                GitHubIssueRefs.normalize("Acme/Widgets#12"));
    }

    @Test
    void allowsAnyRepositoryWhenTheAllowListIsBlank() {
        Set<String> none = GitHubIssueRefs.parseRepoAllowList("  ");
        assertTrue(GitHubIssueRefs.allows(none, "anyone", "anything"));
    }

    @Test
    void narrowsByOwnerOrByExactRepository() {
        Set<String> byOwner = GitHubIssueRefs.parseRepoAllowList("acme");
        assertTrue(GitHubIssueRefs.allows(byOwner, "acme", "widgets"));
        assertFalse(GitHubIssueRefs.allows(byOwner, "other", "widgets"));

        Set<String> byRepo = GitHubIssueRefs.parseRepoAllowList("acme/widgets, acme/tools");
        assertTrue(GitHubIssueRefs.allows(byRepo, "Acme", "Widgets"));
        assertFalse(GitHubIssueRefs.allows(byRepo, "acme", "secrets"));
    }
}
