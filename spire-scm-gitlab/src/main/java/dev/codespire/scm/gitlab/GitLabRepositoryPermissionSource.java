package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.port.RepositoryPermissionSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import java.time.LocalDate;
import java.time.ZoneOffset;
import static dev.codespire.contract.scm.RepositoryPermission.State.*;

/** The all-members endpoint includes inherited and invited membership, unlike direct membership. */
public final class GitLabRepositoryPermissionSource implements RepositoryPermissionSource {
    private final GitLabClient client;
    public GitLabRepositoryPermissionSource(GitLabClient client) { this.client = client; }

    public RepositoryPermission permission(RepoRef repository, String providerUserId) {
        try {
            JsonNode response = client.getIdentityJson("/projects/" + ActorDirectory.encode(repository.workspace() + "/" + repository.slug())
                    + "/members/all/" + ActorDirectory.encode(providerUserId));
            if (!providerUserId.equals(response.path("id").asText())) return unknown();
            if (!"active".equals(response.path("state").asText())) return unknown();
            JsonNode expiry = response.path("expires_at");
            if (!expiry.isNull()
                    && !LocalDate.parse(expiry.asText()).isAfter(LocalDate.now(ZoneOffset.UTC))) {
                return new RepositoryPermission(CANNOT_PUSH, "GitLab membership has expired.");
            }
            JsonNode access = response.path("access_level");
            if (!access.isInt()) return unknown();
            return switch (access.intValue()) {
                case 30, 40, 50 -> new RepositoryPermission(CAN_PUSH, "GitLab reports active effective repository write access.");
                case 0, 5, 10, 15, 20 -> new RepositoryPermission(CANNOT_PUSH, "GitLab reports no repository write access.");
                default -> unknown();
            };
        } catch (RuntimeException failure) { return unknown(); }
    }

    private static RepositoryPermission unknown() {
        return RepositoryPermission.unknown("GitLab effective repository membership is unavailable; check the reviewer's member-read capability.");
    }
}
