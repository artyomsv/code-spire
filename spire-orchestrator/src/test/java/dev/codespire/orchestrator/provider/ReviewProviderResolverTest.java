package dev.codespire.orchestrator.provider;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workspace-collision disambiguation: a review resolves its provider by the SCM type stored on
 * its header (review_status.provider_type), NOT by workspace alone — so a GitHub org and a Bitbucket
 * workspace sharing a name each broker the RIGHT provider. Falls back to workspace-only for reviews
 * that predate a stored type. Collaborators are field-injected, so fakes are set directly.
 */
class ReviewProviderResolverTest {

    private static ScmProvider provider(String type) {
        return new ScmProvider(UUID.randomUUID(), "bot", type, "https://x", "acme", "bearer",
                null, "secret", "acct", true, List.of(), null, null, ProviderRole.REVIEWER);
    }

    private static ReviewProviderResolver resolver(String storedType, ProviderRegistry providers) {
        ReviewProviderResolver r = new ReviewProviderResolver();
        r.providers = providers;
        r.projection = new ReviewProjection() {
            @Override
            public Optional<String> providerTypeOf(String reviewId) {
                return Optional.ofNullable(storedType);
            }
        };
        return r;
    }

    @Test
    void resolvesByStoredTypeWhenPresent() {
        List<String> calls = new ArrayList<>();
        ReviewProviderResolver r = resolver("bitbucket-cloud", new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolve(String type, String workspace) {
                calls.add(type + "@" + workspace);
                return Optional.of(provider(type));
            }

            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                throw new AssertionError("must resolve by (type, workspace) when the review stores a type");
            }
        });

        Optional<ScmProvider> got = r.resolveForReview(ReviewIds.reviewId(new RepoRef("acme", "web"), 3L));

        assertTrue(got.isPresent());
        assertEquals("bitbucket-cloud", got.get().type());
        assertEquals(List.of("bitbucket-cloud@acme"), calls, "disambiguated by the review's stored SCM type");
    }

    @Test
    void fallsBackToWorkspaceWhenNoStoredType() {
        List<String> calls = new ArrayList<>();
        ReviewProviderResolver r = resolver(null, new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                calls.add(workspace);
                return Optional.of(provider("github"));
            }
        });

        Optional<ScmProvider> got = r.resolveForReview(ReviewIds.reviewId(new RepoRef("acme", "web"), 3L));

        assertTrue(got.isPresent());
        assertEquals(List.of("acme"), calls, "no stored type -> workspace-only fallback");
    }

    @Test
    void blankStoredTypeAlsoFallsBackToWorkspace() {
        List<String> calls = new ArrayList<>();
        ReviewProviderResolver r = resolver("", new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                calls.add(workspace);
                return Optional.of(provider("github"));
            }
        });

        r.resolveForReview(ReviewIds.reviewId(new RepoRef("acme", "web"), 3L));

        assertEquals(List.of("acme"), calls, "blank stored type is treated as absent");
    }
}
