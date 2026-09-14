package dev.codespire.context.jira;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ResolvedActor;
import java.util.ArrayList;
import java.util.List;

/** Jira Cloud account IDs have no exact handle counterpart. Selection is always explicit. */
public final class JiraActorDirectory implements ActorDirectory {
    private final JiraClient client;
    public JiraActorDirectory(JiraClient client) { this.client = client; }

    public Result lookup(String input, String scope) {
        String query = ActorDirectory.handle(input);
        if (query.isBlank()) return Result.failed(Status.NOT_FOUND, "Enter a display name.");
        try {
            JsonNode users = client.getIdentityJson("/rest/api/3/user/search?query=" + ActorDirectory.encode(query) + "&maxResults=50");
            if (!users.isArray()) return Result.failed(Status.UNSUPPORTED, "Jira Cloud user search is unavailable. The account needs Browse users and groups and user-read access.");
            List<ResolvedActor> choices = new ArrayList<>();
            for (JsonNode user : users) {
                ResolvedActor actor = actor(user);
                if (actor == null) return Result.failed(Status.UNSUPPORTED, "This Jira response does not provide Cloud account IDs. No display name can be stored as an identity.");
                if (user.path("active").asBoolean(false)) choices.add(actor);
            }
            return choices.isEmpty() ? Result.failed(Status.NOT_FOUND, "No visible active Jira person matches. Check Browse users and groups permission.")
                    : new Result(Status.SELECTION_REQUIRED, List.copyOf(choices), "Select a Jira account; display names are not unique. Up to 50 visible matches are shown; narrow the search if needed.");
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    public Result byId(String id) {
        try {
            JsonNode user = client.getIdentityJson("/rest/api/3/user?accountId=" + ActorDirectory.encode(id));
            ResolvedActor actor = actor(user);
            if (actor == null || !id.equals(actor.providerUserId()) || !user.path("active").asBoolean(false)) {
                return Result.failed(Status.UNAVAILABLE, "Jira did not return the selected active account. Check Browse users and groups permission.");
            }
            return Result.found(actor);
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    private static ResolvedActor actor(JsonNode json) {
        String id = json.path("accountId").asText("");
        String name = json.path("displayName").asText("");
        return id.isBlank() || name.isBlank() ? null : new ResolvedActor(id, "", name);
    }
}
