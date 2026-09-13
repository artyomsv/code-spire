package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.port.RepositoryPermissionSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import java.util.HashSet;
import java.util.Set;
import static dev.codespire.contract.scm.RepositoryPermission.State.*;

/** Effective rights include groups. This read requires a repository-admin caller; no token fallback. */
public final class BitbucketRepositoryPermissionSource implements RepositoryPermissionSource {
    private final BitbucketCloudClient client;
    public BitbucketRepositoryPermissionSource(BitbucketCloudClient client) { this.client = client; }

    public RepositoryPermission permission(RepoRef repository, String providerUserId) {
        try {
            JsonNode identity = client.getIdentityJson("/users/" + ActorDirectory.encode(providerUserId));
            if (!providerUserId.equals(identity.path("account_id").asText())) return unknown();
            String uuid = identity.path("uuid").asText("");
            if (uuid.isBlank()) return unknown();
            JsonNode page = client.getIdentityJson("/workspaces/" + ActorDirectory.encode(repository.workspace())
                    + "/permissions/repositories/" + ActorDirectory.encode(repository.slug()) + "?pagelen=100");
            Set<String> visited = new HashSet<>();
            RepositoryPermission.State found = CANNOT_PUSH;
            boolean matched = false;
            for (int count = 1; ; count++) {
                JsonNode values = page.path("values");
                if (!values.isArray()) return unknown();
                for (JsonNode entry : values) {
                    String rowUuid = entry.path("user").path("uuid").asText("");
                    if (rowUuid.isBlank()) return unknown();
                    if (!(repository.workspace() + "/" + repository.slug()).equals(entry.path("repository").path("full_name").asText())) return unknown();
                    if (!uuid.equals(rowUuid)) continue;
                    JsonNode account = entry.path("user").path("account_id");
                    if (!account.isMissingNode() && !providerUserId.equals(account.asText())) return unknown();
                    RepositoryPermission.State effective = switch (entry.path("permission").asText()) {
                        case "admin", "write" -> CAN_PUSH;
                        case "read" -> CANNOT_PUSH;
                        default -> UNKNOWN;
                    };
                    if (effective == UNKNOWN || (matched && found != effective)) return unknown();
                    found = effective;
                    matched = true;
                }
                JsonNode next = page.path("next");
                if (next.isMissingNode() || next.isNull()) return new RepositoryPermission(found, "Bitbucket reports complete effective repository permissions.");
                if (!next.isTextual() || next.asText().isBlank() || !visited.add(next.asText())) return unknown();
                if (count >= 10) return unknown();
                page = client.getIdentityPage(next.asText());
            }
        } catch (RuntimeException failure) { return unknown(); }
    }

    private static RepositoryPermission unknown() {
        return RepositoryPermission.unknown("Bitbucket effective repository permission is unavailable. This capability requires repository-admin access for the selected reviewer; configure that capability or an explicit /fix override.");
    }
}
