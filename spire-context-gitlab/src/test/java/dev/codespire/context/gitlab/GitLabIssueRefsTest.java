package dev.codespire.context.gitlab;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitLab's grammar carries three sigils where GitHub has one, and its namespaces nest. These tests
 * pin the sigil-to-kind mapping and the nesting, because getting either wrong silently fetches the
 * wrong object type or the wrong project.
 */
class GitLabIssueRefsTest {

    @Test
    void mapsEachSigilToItsOwnKind() {
        assertEquals(GitLabIssueRefs.Kind.ISSUE, GitLabIssueRefs.parse("#12").orElseThrow().kind());
        assertEquals(GitLabIssueRefs.Kind.MERGE_REQUEST, GitLabIssueRefs.parse("!34").orElseThrow().kind());
        assertEquals(GitLabIssueRefs.Kind.EPIC, GitLabIssueRefs.parse("&7").orElseThrow().kind());
    }

    @Test
    void findsAllThreeSigilsInProse() {
        assertEquals(Set.of("#12", "!34", "&7"),
                GitLabIssueRefs.candidates("closes #12, follows !34, part of &7"));
    }

    /** A sigil glued to a word is punctuation, not a reference. */
    @Test
    void ignoresSigilsInsideWords() {
        assertTrue(GitLabIssueRefs.candidates("abc#1 x!2 y&3").isEmpty());
    }

    /**
     * A URL fragment is not a qualified reference. Without the {@code (?<!/)} guard on the qualified
     * pattern, {@code http://x/y#3} yields the false candidate {@code x/y#3} — the GitHub adapter's
     * equivalent test caught exactly that, and this grammar has the same shape.
     */
    @Test
    void ignoresAQualifiedLookAlikeInsideAUrlFragment() {
        assertTrue(GitLabIssueRefs.candidates("http://x/y#3").isEmpty());
    }

    /** But a colon before a qualified reference is prose, not a URL — it must still extract. */
    @Test
    void stillFindsAQualifiedReferenceAfterAColon() {
        assertEquals(Set.of("acme/widgets#12"), GitLabIssueRefs.candidates("ref:acme/widgets#12"));
    }

    /** Nested groups are the normal case on GitLab, so the qualified form must accept many slashes. */
    @Test
    void parsesAQualifiedReferenceAcrossNestedGroups() {
        GitLabIssueRefs.Ref ref = GitLabIssueRefs.parse("acme/tools/widgets#12").orElseThrow();
        assertFalse(ref.isProjectRelative());
        assertEquals("acme/tools/widgets", ref.projectPath());
        assertEquals(12, ref.number());
        assertEquals(GitLabIssueRefs.Kind.ISSUE, ref.kind());
    }

    @Test
    void parsesTheThreeUrlShapesIncludingOnASelfManagedHost() {
        GitLabIssueRefs.Ref issue = GitLabIssueRefs
                .parse("https://gitlab.example.invalid/acme/tools/widgets/-/issues/12").orElseThrow();
        assertEquals("acme/tools/widgets", issue.projectPath());
        assertEquals(GitLabIssueRefs.Kind.ISSUE, issue.kind());

        GitLabIssueRefs.Ref mr = GitLabIssueRefs
                .parse("https://gitlab.com/acme/widgets/-/merge_requests/34").orElseThrow();
        assertEquals(GitLabIssueRefs.Kind.MERGE_REQUEST, mr.kind());
        assertEquals(34, mr.number());

        GitLabIssueRefs.Ref epic = GitLabIssueRefs
                .parse("https://gitlab.com/groups/acme/-/epics/7").orElseThrow();
        assertEquals(GitLabIssueRefs.Kind.EPIC, epic.kind());
        assertEquals("acme", epic.projectPath());
    }

    @Test
    void parsesNothingFromAnotherSourcesReference() {
        assertEquals(Optional.empty(), GitLabIssueRefs.parse("PROJ-123"));
        assertEquals(Optional.empty(), GitLabIssueRefs.parse("plain text"));
    }

    @Test
    void capsCandidates() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            text.append(" #").append(i);
        }
        assertEquals(10, GitLabIssueRefs.candidates(text.toString()).size());
    }

    @Test
    void normalizesCaseAndTrailingSlash() {
        assertEquals(GitLabIssueRefs.normalize("Acme/Widgets#12"),
                GitLabIssueRefs.normalize("acme/widgets#12"));
        assertEquals(GitLabIssueRefs.normalize("https://gitlab.com/acme/widgets/-/issues/1/"),
                GitLabIssueRefs.normalize("https://gitlab.com/acme/widgets/-/issues/1"));
    }

    /**
     * An epic belongs to a group, and a project path does not say which ancestor owns it. Nearest
     * first, then the top-level group — ordered, because the provider tries them in turn.
     */
    @Test
    void listsAncestorGroupsNearestFirst() {
        assertEquals(List.of("acme/tools", "acme"), GitLabIssueRefs.ancestorGroups("acme/tools/widgets"));
        assertEquals(List.of("acme"), GitLabIssueRefs.ancestorGroups("acme/widgets"));
        assertEquals(List.of(), GitLabIssueRefs.ancestorGroups("widgets"));
    }

    @Test
    void narrowsByGroupPrefixOrExactProject() {
        Set<String> byGroup = GitLabIssueRefs.parseProjectAllowList("acme");
        assertTrue(GitLabIssueRefs.allows(byGroup, "acme/tools/widgets"));
        assertFalse(GitLabIssueRefs.allows(byGroup, "other/widgets"));

        Set<String> exact = GitLabIssueRefs.parseProjectAllowList("acme/widgets");
        assertTrue(GitLabIssueRefs.allows(exact, "Acme/Widgets"));
        assertFalse(GitLabIssueRefs.allows(exact, "acme/secrets"));

        assertTrue(GitLabIssueRefs.allows(GitLabIssueRefs.parseProjectAllowList(""), "anyone/anything"));
    }
}
