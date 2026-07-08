package dev.codespire.scm.github;

import dev.codespire.contract.scm.PrCoordinates;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubPrUrlParserTest {

    private final GitHubPrUrlParser parser = new GitHubPrUrlParser();

    @Test
    void parsesAGitHubPrUrl() {
        PrCoordinates c = parser.parse("https://github.com/owner/repo/pull/42").orElseThrow();
        assertEquals("owner", c.repo().workspace());
        assertEquals("repo", c.repo().slug());
        assertEquals(42, c.prId());
    }

    @Test
    void parsesEnterpriseHostAndTrailingSegments() {
        PrCoordinates c = parser.parse("https://ghe.acme.com/team/svc/pull/7/files?w=1").orElseThrow();
        assertEquals("team", c.repo().workspace());
        assertEquals("svc", c.repo().slug());
        assertEquals(7, c.prId());
    }

    @Test
    void ignoresNonGitHubShapes() {
        assertTrue(parser.parse("https://bitbucket.org/acme/web/pull-requests/5").isEmpty());
        assertTrue(parser.parse("https://gitlab.com/grp/proj/-/merge_requests/9").isEmpty());
        assertTrue(parser.parse("not a url").isEmpty());
        assertEquals(Optional.empty(), parser.parse(null));
    }
}
