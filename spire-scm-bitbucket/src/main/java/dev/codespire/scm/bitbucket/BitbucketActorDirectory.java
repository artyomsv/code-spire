package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ResolvedActor;
import java.util.ArrayList;
import java.util.List;

/** Nicknames are not unique identifiers. A caller must explicitly select a returned stable account. */
public final class BitbucketActorDirectory implements ActorDirectory {
    private final BitbucketCloudClient client;
    public BitbucketActorDirectory(BitbucketCloudClient client) { this.client = client; }

    public Result lookup(String input, String scope) {
        if (scope == null || scope.isBlank()) return Result.failed(Status.UNSUPPORTED,
                "Select a repository to search its workspace members. Bitbucket nicknames are not unique handles; the credential needs account/read:workspace:bitbucket access.");
        String query = ActorDirectory.handle(input);
        if (query.isBlank()) return Result.failed(Status.NOT_FOUND, "Enter a nickname or display name.");
        try {
            List<ResolvedActor> matches = new ArrayList<>();
            for (int page = 1; page <= 10; page++) {
                JsonNode response = client.getIdentityJson("/workspaces/" + ActorDirectory.encode(scope) + "/members?pagelen=100&page=" + page);
                JsonNode members = response.path("values");
                if (!members.isArray()) return Result.failed(Status.UNAVAILABLE, "Bitbucket returned an incomplete member list.");
                for (JsonNode member : members) {
                    ResolvedActor actor = actor(member.path("user"));
                    if (actor == null) return Result.failed(Status.UNAVAILABLE, "Bitbucket returned an incomplete identity.");
                    if (actor.handle().equalsIgnoreCase(query) || actor.displayName().equalsIgnoreCase(query)) matches.add(actor);
                }
                if (response.path("next").asText("").isBlank()) return matches.isEmpty()
                        ? Result.failed(Status.NOT_FOUND, "No visible workspace member matches. Check the selected credential's workspace-read access.")
                        : new Result(Status.SELECTION_REQUIRED, List.copyOf(matches), "Select the intended Bitbucket account. Nicknames can be shared.");
            }
            return Result.failed(Status.UNAVAILABLE, "Bitbucket member pagination exceeded the lookup limit; no identity was selected.");
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    public Result byId(String id) {
        try {
            ResolvedActor actor = actor(client.getIdentityJson("/users/" + ActorDirectory.encode(id)));
            if (actor == null || !id.equals(actor.providerUserId())) return Result.failed(Status.UNAVAILABLE, "Bitbucket returned a different or incomplete account. User reads require read:user:bitbucket access.");
            return Result.found(actor);
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    private static ResolvedActor actor(JsonNode json) {
        String id = json.path("account_id").asText("");
        String handle = json.path("nickname").asText("");
        String name = json.path("display_name").asText("");
        return id.isBlank() || (handle.isBlank() && name.isBlank()) ? null : new ResolvedActor(id, handle, name);
    }
}
