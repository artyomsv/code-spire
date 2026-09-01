package dev.codespire.workspace;

import java.util.List;
import java.util.Objects;

/** Why a push was allowed or refused. A refusal names every blocked path, not just the first. */
public record PushDecision(boolean allowed, List<String> blocked) {

    public PushDecision {
        blocked = List.copyOf(Objects.requireNonNull(blocked, "blocked"));
        if (allowed && !blocked.isEmpty()) {
            throw new IllegalArgumentException("an allowed push cannot name blocked paths: " + blocked);
        }
        if (!allowed && blocked.isEmpty()) {
            throw new IllegalArgumentException("a refusal must say what it refused");
        }
    }

    public static PushDecision allow() {
        return new PushDecision(true, List.of());
    }

    public static PushDecision refuse(List<String> blocked) {
        return new PushDecision(false, List.copyOf(blocked));
    }
}
