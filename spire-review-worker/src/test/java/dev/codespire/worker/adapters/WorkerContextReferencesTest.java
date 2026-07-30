package dev.codespire.worker.adapters;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerContextReferencesTest {

    private final WorkerContextReferences references = new WorkerContextReferences();

    /**
     * Every source's extractor must be registered here, because a missing one contributes nothing and
     * fails silently — the pipeline would simply never produce a candidate for it, with no error.
     *
     * <p>This asserts on the registry rather than on the union of what the extractors produce, because
     * the union cannot discriminate between them: GitLab's patterns are supersets of GitHub's, and the
     * Confluence extractor emits every {@code https://} URL. So no input string is produced by only one
     * source, and a union assertion would still pass with an extractor dropped.
     */
    @Test
    void registersEverySourcesExtractor() {
        assertEquals(
                Set.of("JIRA", "CONFLUENCE", "GITHUB_ISSUES", "GITLAB_ISSUES"),
                references.registeredSources());
    }

    /** And the union still has to actually work across the syntaxes those extractors own. */
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
