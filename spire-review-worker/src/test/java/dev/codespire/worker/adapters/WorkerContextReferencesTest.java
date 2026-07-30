package dev.codespire.worker.adapters;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition root's whole job is naming every extractor. A source whose extractor is missing
 * here contributes nothing and fails silently — the pipeline would simply never produce a candidate
 * for it, with no error anywhere. So the union is asserted directly.
 */
class WorkerContextReferencesTest {

    private final WorkerContextReferences references = new WorkerContextReferences();

    @Test
    void unionsEveryRegisteredSourcesReferencesFromOneText() {
        Set<String> found = references.referencesIn(
                "PROJ-12 fixes #34 and acme/widgets#56, see https://acme.atlassian.net/wiki/pages/78/Spec "
                        + "plus !90 and &11");

        assertTrue(found.contains("PROJ-12"), "Jira keys");
        assertTrue(found.contains("#34"), "a bare issue reference");
        assertTrue(found.contains("acme/widgets#56"), "a qualified issue reference");
        assertTrue(found.stream().anyMatch(r -> r.contains("/pages/78/")), "Confluence links");
        assertTrue(found.contains("!90"), "a GitLab merge request");
        assertTrue(found.contains("&11"), "a GitLab epic");
    }
}
