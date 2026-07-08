package dev.codespire.scm.bitbucket;

import dev.codespire.contract.scm.PrCoordinates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitbucketCloudPrUrlParserTest {

    private final BitbucketCloudPrUrlParser parser = new BitbucketCloudPrUrlParser();

    @Test
    void parsesTheCanonicalAndUiVariants() {
        PrCoordinates a = parser.parse("https://bitbucket.org/acme/web/pull-requests/5").orElseThrow();
        assertEquals("acme", a.repo().workspace());
        assertEquals("web", a.repo().slug());
        assertEquals(5, a.prId());

        // the UI sometimes renders "/pullrequests/"
        assertEquals(5, parser.parse("https://bitbucket.org/acme/web/pullrequests/5").orElseThrow().prId());
    }

    @Test
    void parsesProxiedHosts() {
        // company MCAS proxy host still parses (host-agnostic)
        PrCoordinates c = parser.parse("https://bitbucket-org.mcas.ms/acme/web/pull-requests/8").orElseThrow();
        assertEquals("acme", c.repo().workspace());
        assertEquals(8, c.prId());
    }

    @Test
    void ignoresNonBitbucketShapes() {
        assertTrue(parser.parse("https://github.com/owner/repo/pull/42").isEmpty());
        assertTrue(parser.parse("https://gitlab.com/grp/proj/-/merge_requests/9").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }
}
