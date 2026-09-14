package dev.codespire.orchestrator.provider;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryForgeOriginTest {
    @Test void mapsPublicWebOriginsWithoutRewritingSelfHostedOrigins() {
        assertEquals("https://api.github.com", ProviderClients.repositoryForgeOrigin("github", "https://github.com/TEST/repo/pull/1"));
        assertEquals("https://api.bitbucket.org", ProviderClients.repositoryForgeOrigin("bitbucket-cloud", "https://bitbucket.org/TEST/repo/pull-requests/1"));
        assertEquals("https://test-forge.example.test", ProviderClients.repositoryForgeOrigin("gitlab", "https://TEST-forge.example.test/TEST/nested/repo/-/merge_requests/1"));
        assertEquals("https://test-forge.example.test", ProviderClients.repositoryForgeOrigin("github", "https://TEST-forge.example.test/TEST/repo/pull/1"));
        assertEquals("https://github.com", ProviderClients.repositoryForgeOrigin("gitlab", "https://github.com/TEST/repo"));
    }
}
