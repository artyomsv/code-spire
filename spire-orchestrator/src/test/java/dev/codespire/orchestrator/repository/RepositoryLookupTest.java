package dev.codespire.orchestrator.repository;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** Cutover lookup fixtures belong only to disposable Dev Services and use the inherited TEST cleanup. */
@QuarkusTest
class RepositoryLookupTest extends RepositoryFixture {
    @Test void legacyNestedReviewCoordinatesFindTheCanonicalRepository() {
        RepositoryView saved = repositories.create(repository(null, null));
        int first = workspace.indexOf('/');
        assertEquals(saved.id(), repositories.find("gitlab", origin, workspace.substring(0, first),
                workspace.substring(first + 1) + "/TEST-repo").orElseThrow().id());
    }
    @Test void canonicalOriginFindsRegisteredRepository() {
        RepositoryView saved = repositories.create(repository(null, null));
        assertEquals(saved.id(), repositories.find("gitlab", origin.toUpperCase(Locale.ROOT) + ":443/api/v4/",
                workspace, "TEST-repo").map(RepositoryView::id).orElse(null));
    }

    @Test void samePathOnAnotherOriginCannotMatch() {
        assertExistingRepositoryCanBeFound();
        assertTrue(repositories.find("gitlab", "https://TEST-other.example.test", workspace, "TEST-repo").isEmpty());
    }

    @Test void samePathOnAnotherForgeKindCannotMatch() {
        assertExistingRepositoryCanBeFound();
        assertTrue(repositories.find("github", origin, workspace, "TEST-repo").isEmpty());
    }

    @Test void anotherNamespaceCannotMatch() {
        assertExistingRepositoryCanBeFound();
        assertTrue(repositories.find("gitlab", origin, workspace + "/TEST-other", "TEST-repo").isEmpty());
    }

    @Test void anotherSlugCannotMatch() {
        assertExistingRepositoryCanBeFound();
        assertTrue(repositories.find("gitlab", origin, workspace, "TEST-other").isEmpty());
    }

    private void assertExistingRepositoryCanBeFound() {
        RepositoryView saved = repositories.create(repository(null, null));
        assertEquals(saved.id(), repositories.find("gitlab", origin, workspace, "TEST-repo")
                .map(RepositoryView::id).orElse(null), "the refusal assertion must have a real matching row to distinguish");
    }
}
