package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ResolvedActor;

public final class GitHubActorDirectory implements ActorDirectory {
    private final GitHubClient client;
    public GitHubActorDirectory(GitHubClient client) { this.client = client; }

    public Result lookup(String input, String scope) {
        String handle = ActorDirectory.handle(input);
        if (handle.isBlank()) return Result.failed(Status.NOT_FOUND, "Enter a handle.");
        try {
            ResolvedActor actor = actor(client.getIdentityJson("/users/" + ActorDirectory.encode(handle)));
            if (actor == null) return Result.failed(Status.UNAVAILABLE, "GitHub returned an incomplete identity.");
            if (!actor.handle().equalsIgnoreCase(handle)) return Result.failed(Status.NOT_FOUND, "GitHub did not return the exact handle.");
            return Result.found(actor);
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    public Result byId(String id) {
        try {
            ResolvedActor actor = actor(client.getIdentityJson("/user/" + ActorDirectory.encode(id)));
            if (actor == null || !id.equals(actor.providerUserId())) return Result.failed(Status.UNAVAILABLE, "GitHub returned a different or incomplete identity.");
            return Result.found(actor);
        } catch (RuntimeException failure) { return ActorDirectory.failure(failure); }
    }

    private static ResolvedActor actor(JsonNode json) {
        String id = json.path("id").asText("");
        String handle = json.path("login").asText("");
        return id.isBlank() || handle.isBlank() ? null : new ResolvedActor(id, handle, json.path("name").asText(handle));
    }
}
