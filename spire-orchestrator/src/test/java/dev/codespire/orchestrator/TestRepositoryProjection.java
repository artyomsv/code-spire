package dev.codespire.orchestrator;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import java.util.Optional;
import java.util.UUID;

/** Mapped repository fixture for saga policy tests; the cutover suite exercises the real DB claim. */
public class TestRepositoryProjection extends ReviewProjection {
    public static final UUID REPOSITORY_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Override public Optional<UUID> repositoryIdOf(String reviewId) { return Optional.of(REPOSITORY_ID); }
    @Override public boolean claimRepository(String reviewId, UUID repositoryId, RepoRef repo, long prId) {
        return REPOSITORY_ID.equals(repositoryId);
    }
}
