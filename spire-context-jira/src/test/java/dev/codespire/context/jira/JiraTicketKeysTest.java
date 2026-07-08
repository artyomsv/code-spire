package dev.codespire.context.jira;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraTicketKeysTest {

    @Test
    void extractsCandidatesFromTitleBranchAndDescription() {
        Set<String> keys = JiraTicketKeys.candidates("CS-42: fix", "feature/PROJ-7-widget", "closes AB-100");
        assertEquals(Set.of("CS-42", "PROJ-7", "AB-100"), keys);
    }

    @Test
    void capturesLongProjectKeys() {
        assertEquals(Set.of("ACME-12345"), JiraTicketKeys.candidates("fix/ACME-12345-thing"));
    }

    @Test
    void parseProjectKeysNormalizesAndSplits() {
        assertEquals(Set.of("ACME", "PROJ"), JiraTicketKeys.parseProjectKeys("acme, proj"));
        assertTrue(JiraTicketKeys.parseProjectKeys("  ").isEmpty());
        assertTrue(JiraTicketKeys.parseProjectKeys(null).isEmpty());
    }

    @Test
    void filterKeepsOnlyConfiguredProjectsAndPassesAllWhenUnset() {
        Set<String> candidates = Set.of("CS-1", "AB-2", "ACME-9");
        assertEquals(Set.of("CS-1", "ACME-9"),
                JiraTicketKeys.filter(candidates, Set.of("CS", "ACME")));
        assertEquals(candidates, JiraTicketKeys.filter(candidates, Set.of()));
    }

    @Test
    void resolvePreviewAcceptsAFullKey() {
        assertEquals(Set.of("ACME-12345"),
                JiraTicketKeys.resolvePreview("ACME-12345", Set.of("ACME")));
    }

    @Test
    void resolvePreviewBuildsAKeyFromABareNumberUsingProjectKeys() {
        assertEquals(Set.of("ACME-12345"),
                JiraTicketKeys.resolvePreview("12345", Set.of("ACME")));
    }

    @Test
    void resolvePreviewIsEmptyForABareNumberWithoutProjectKeys() {
        assertTrue(JiraTicketKeys.resolvePreview("12345", Set.of()).isEmpty());
    }
}
