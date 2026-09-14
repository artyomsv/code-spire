package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.port.RepositoryPermissionSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import static dev.codespire.contract.scm.RepositoryPermission.State.*;

/** Effective base permission includes team and organization access; custom role names grant nothing. */
public final class GitHubRepositoryPermissionSource implements RepositoryPermissionSource {
    private final GitHubClient client;
    public GitHubRepositoryPermissionSource(GitHubClient client) { this.client = client; }

    public RepositoryPermission permission(RepoRef repository, String providerUserId) {
        try {
            ActorDirectory.Result identity = new GitHubActorDirectory(client).byId(providerUserId);
            if (identity.status() != ActorDirectory.Status.FOUND) return unknown();
            String handle = identity.actors().getFirst().handle();
            JsonNode response = client.getIdentityJson("/repos/" + ActorDirectory.encode(repository.workspace())
                    + "/" + ActorDirectory.encode(repository.slug()) + "/collaborators/" + ActorDirectory.encode(handle) + "/permission");
            if (!providerUserId.equals(response.path("user").path("id").asText())) return unknown();
            return switch (response.path("permission").asText()) {
                case "admin", "write" -> new RepositoryPermission(CAN_PUSH, "GitHub reports effective repository write access.");
                case "read", "none" -> new RepositoryPermission(CANNOT_PUSH, "GitHub reports no repository write access.");
                default -> unknown();
            };
        } catch (RuntimeException failure) { return unknown(); }
    }

    private static RepositoryPermission unknown() {
        return RepositoryPermission.unknown("GitHub effective repository permission is unavailable; the reviewer needs access to repository metadata.");
    }
}
