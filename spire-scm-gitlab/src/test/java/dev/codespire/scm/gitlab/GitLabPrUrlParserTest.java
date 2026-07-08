package dev.codespire.scm.gitlab;

import dev.codespire.contract.scm.PrCoordinates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLabPrUrlParserTest {

    private final GitLabPrUrlParser parser = new GitLabPrUrlParser();

    @Test
    void parsesAGitLabMrUrl() {
        PrCoordinates c = parser.parse("https://gitlab.com/artyomsv-group/code-review-poc/-/merge_requests/3")
                .orElseThrow();
        assertEquals("artyomsv-group", c.repo().workspace());
        assertEquals("code-review-poc", c.repo().slug());
        assertEquals(3, c.prId());
    }

    @Test
    void parsesSelfManagedCompanyHosts() {
        // Host-agnostic: works for any self-managed GitLab, not just gitlab.com.
        PrCoordinates a = parser.parse("https://gitlab.internal.example/my-team/my-project/-/merge_requests/12").orElseThrow();
        assertEquals("my-team", a.repo().workspace());
        assertEquals("my-project", a.repo().slug());
        assertEquals(12, a.prId());

        PrCoordinates b = parser.parse("https://git.corp.example/dept/team/svc/-/merge_requests/4").orElseThrow();
        assertEquals("dept", b.repo().workspace());
        assertEquals("team/svc", b.repo().slug()); // nested group preserved
        assertEquals(4, b.prId());
    }

    @Test
    void keepsTheNestedSubGroupInTheSlug() {
        PrCoordinates c = parser.parse("https://gitlab.example.com/team/sub/proj/-/merge_requests/9").orElseThrow();
        assertEquals("team", c.repo().workspace());
        assertEquals("sub/proj", c.repo().slug()); // top group = workspace, rest = slug
        assertEquals(9, c.prId());
    }

    @Test
    void rejectsATraversalSegmentAndAGroupOnlyPath() {
        assertTrue(parser.parse("https://gitlab.com/team/../etc/-/merge_requests/1").isEmpty());
        assertTrue(parser.parse("https://gitlab.com/lonelygroup/-/merge_requests/1").isEmpty());
    }

    @Test
    void ignoresNonGitLabShapes() {
        assertTrue(parser.parse("https://github.com/owner/repo/pull/42").isEmpty());
        assertTrue(parser.parse("https://bitbucket.org/acme/web/pull-requests/5").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }
}
