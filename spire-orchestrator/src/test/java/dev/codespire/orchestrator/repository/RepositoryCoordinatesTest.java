package dev.codespire.orchestrator.repository;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryCoordinatesTest {
    RepositoryRegistry registry = new RepositoryRegistry();
    RepositoryInput input(String workspace, String slug) {
        return new RepositoryInput("gitlab", "https://TEST-forge.example.test", workspace, slug, true, null, null);
    }
    @Test void refusesTraversalAndEmptySegments() {
        for (String path : new String[]{"TEST/../other", "TEST//other", "TEST/./other", "TEST\\other", "TEST/\u0001other"}) {
            assertThrows(IllegalArgumentException.class, () -> registry.normalize(input(path, "TEST-repo")));
        }
    }
    @Test void refusesNamespaceInsideSlug() {
        assertThrows(IllegalArgumentException.class, () -> registry.normalize(input("TEST/group", "TEST/repo")));
    }
    @Test void requiresCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> registry.normalize(input("", "TEST-repo")));
    }
}
