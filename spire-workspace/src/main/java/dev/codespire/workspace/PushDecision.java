package dev.codespire.workspace;

import java.util.List;
import java.util.Objects;

/**
 * Why a push was allowed or refused. A refusal names every blocked path, not just the first.
 *
 * <p>Blocked entries are {@link ChangedPath}s rather than bare strings so a refusal says what
 * happened to each file. "{@code .github/workflows/ci.yml} was blocked" does not tell an operator
 * whether the factory edited that workflow or deleted it, and those call for different responses.
 */
public record PushDecision(boolean allowed, List<ChangedPath> blocked) {

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

    /** The compact constructor already copies, so this does not copy again. */
    public static PushDecision refuse(List<ChangedPath> blocked) {
        return new PushDecision(false, blocked);
    }

    /** Just the paths, for a message or an assertion that does not care about the kinds. */
    public List<String> blockedPaths() {
        return blocked.stream().map(ChangedPath::path).toList();
    }
}
