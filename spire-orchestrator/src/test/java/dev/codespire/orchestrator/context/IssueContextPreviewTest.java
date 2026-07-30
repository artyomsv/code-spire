package dev.codespire.orchestrator.context;

import dev.codespire.context.github.GitHubIssueRefs;
import dev.codespire.context.gitlab.GitLabIssueRefs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preview has no pull request, so it has no repository to resolve a bare reference against. The
 * operator must be told that in a way that says what to type instead — an empty result would read as
 * "the integration is broken" when the input was merely under-specified.
 */
class IssueContextPreviewTest {

    @Test
    void aBareReferenceCannotBeResolvedWithoutARepository() {
        assertTrue(GitHubIssueRefs.parse("#123").orElseThrow().isRepoRelative());
        assertTrue(GitLabIssueRefs.parse("#123").orElseThrow().isProjectRelative());
    }

    @Test
    void aQualifiedReferenceOrUrlCarriesItsOwnRepository() {
        assertEquals("acme", GitHubIssueRefs.parse("acme/widgets#1").orElseThrow().owner());
        assertEquals("acme/tools/widgets",
                GitLabIssueRefs.parse("acme/tools/widgets#1").orElseThrow().projectPath());
        assertEquals("widgets",
                GitHubIssueRefs.parse("https://github.com/acme/widgets/issues/1").orElseThrow().repo());
    }

    @Test
    void theBareReferenceGuidanceNamesBothWaysToFixTheInput() {
        String guidance = ContextProviderResource.BARE_REFERENCE_GUIDANCE;
        assertTrue(guidance.contains("owner/repo#123"), "the qualified form");
        assertTrue(guidance.toLowerCase().contains("url"), "or pasting the URL");
    }
}
