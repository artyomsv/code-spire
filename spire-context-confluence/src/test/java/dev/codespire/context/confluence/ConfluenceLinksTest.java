package dev.codespire.context.confluence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfluenceLinksTest {

    @Test
    void extractsUrlCandidatesFromTitleAndDescription() {
        Set<String> links = ConfluenceLinks.candidates(
                "See https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Design",
                "feature/thing",
                "context: https://acme.atlassian.net/wiki/pages/viewpage.action?pageId=67890 and http://x.test/a");
        assertEquals(Set.of(
                "https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Design",
                "https://acme.atlassian.net/wiki/pages/viewpage.action?pageId=67890",
                "http://x.test/a"), links);
    }

    @Test
    void trimsTrailingPunctuationAroundUrls() {
        Set<String> links = ConfluenceLinks.candidates("see (https://acme.atlassian.net/wiki/spaces/E/pages/9/D).");
        assertEquals(Set.of("https://acme.atlassian.net/wiki/spaces/E/pages/9/D"), links);
    }

    @Test
    void pageIdFromPrettyUrlAndViewpageParam() {
        assertEquals("12345",
                ConfluenceLinks.pageId("https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Design").orElseThrow());
        assertEquals("67890",
                ConfluenceLinks.pageId("https://acme.atlassian.net/wiki/pages/viewpage.action?pageId=67890").orElseThrow());
        assertTrue(ConfluenceLinks.pageId("https://acme.atlassian.net/wiki/x/AbCd").isEmpty());
    }

    @Test
    void pageIdsKeepsOnlyConfiguredHost() {
        Set<String> links = Set.of(
                "https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Design",
                "https://other.example.com/wiki/pages/999999/Other",
                "https://acme.atlassian.net/wiki/pages/viewpage.action?pageId=67890");
        assertEquals(Set.of("12345", "67890"),
                ConfluenceLinks.pageIds(links, "https://acme.atlassian.net/wiki"));
    }

    @Test
    void parseSpaceKeysNormalizesAndSplits() {
        assertEquals(Set.of("ENG", "DOC"), ConfluenceLinks.parseSpaceKeys("eng, doc"));
        assertTrue(ConfluenceLinks.parseSpaceKeys("  ").isEmpty());
        assertTrue(ConfluenceLinks.parseSpaceKeys(null).isEmpty());
    }

    @Test
    void resolvePreviewAcceptsAFullUrl() {
        assertEquals(Set.of("12345"),
                ConfluenceLinks.resolvePreview("https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Design",
                        "https://acme.atlassian.net/wiki"));
    }

    @Test
    void resolvePreviewTreatsABareNumberAsAPageId() {
        assertEquals(Set.of("12345"),
                ConfluenceLinks.resolvePreview("12345", "https://acme.atlassian.net/wiki"));
    }
}
