package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ResolvedActor;
import java.util.ArrayList;
import java.util.List;

public final class GitLabActorDirectory implements ActorDirectory {
    private final GitLabClient client;
    public GitLabActorDirectory(GitLabClient client) { this.client = client; }

    public Result lookup(String input, String scope) {
        String handle = ActorDirectory.handle(input);
        if (handle.isBlank()) return Result.failed(Status.NOT_FOUND, "Enter a handle.");
        try {
            JsonNode users = client.getIdentityJson("/users?username=" + ActorDirectory.encode(handle) + "&per_page=100");
            if (!users.isArray() || users.size() >= 100) return Result.failed(Status.UNAVAILABLE, "GitLab returned an incomplete directory result.");
            List<ResolvedActor> matches = new ArrayList<>();
            for (JsonNode user : users) {
                ResolvedActor actor = actor(user);
                if (actor == null) return Result.failed(Status.UNAVAILABLE, "GitLab returned an incomplete identity.");
                if (actor.handle().equalsIgnoreCase(handle)) matches.add(actor);
            }
            if (matches.size() > 1) return Result.failed(Status.AMBIGUOUS, "GitLab returned more than one exact identity.");
            return matches.isEmpty() ? Result.failed(Status.NOT_FOUND, "No exact GitLab handle was found.") : Result.found(matches.getFirst());
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    public Result byId(String id) {
        try {
            ResolvedActor actor = actor(client.getIdentityJson("/users/" + ActorDirectory.encode(id)));
            if (actor == null || !id.equals(actor.providerUserId())) return Result.failed(Status.UNAVAILABLE, "GitLab returned a different or incomplete identity.");
            return Result.found(actor);
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    private static ResolvedActor actor(JsonNode json) {
        String id = json.path("id").asText("");
        String handle = json.path("username").asText("");
        return id.isBlank() || handle.isBlank() ? null : new ResolvedActor(id, handle, json.path("name").asText(handle));
    }
}
