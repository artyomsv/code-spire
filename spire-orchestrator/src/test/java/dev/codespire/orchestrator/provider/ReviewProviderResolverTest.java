package dev.codespire.orchestrator.provider;

import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReviewProviderResolverTest {
    @Test void resolvesOnlyThePersistedRepositoryAndReviewerRole() {
        UUID selected = UUID.randomUUID();
        ScmProvider provider = new ScmProvider(UUID.randomUUID(), "TEST-account", "gitlab", "https://TEST.example.test",
                "bearer", null, "TEST-secret", "TEST-bot", true, List.of(), "TEST-bot", null, ProviderRole.REVIEWER);
        ReviewProviderResolver resolver = new ReviewProviderResolver();
        resolver.projection = new ReviewProjection() {
            @Override public Optional<UUID> repositoryIdOf(String reviewId) { return Optional.of(selected); }
        };
        resolver.accounts = new RepositoryAccounts() {
            @Override public Optional<ScmProvider> resolve(UUID repositoryId, ProviderRole role) {
                assertEquals(selected, repositoryId);
                assertEquals(ProviderRole.REVIEWER, role);
                return Optional.of(provider);
            }
        };
        assertEquals(Optional.of(provider), resolver.resolveForReview("TEST/repo#1"));
    }

    @Test void unmappedHistoryDoesNotResolveAnAccount() {
        ReviewProviderResolver resolver = new ReviewProviderResolver();
        resolver.projection = new ReviewProjection() {
            @Override public Optional<UUID> repositoryIdOf(String reviewId) { return Optional.empty(); }
        };
        resolver.accounts = new RepositoryAccounts() {
            @Override public Optional<ScmProvider> resolve(UUID repositoryId, ProviderRole role) {
                fail("An unmapped review must not select an account"); return Optional.empty();
            }
        };
        assertTrue(resolver.resolveForReview("TEST/repo#1").isEmpty());
    }
}
