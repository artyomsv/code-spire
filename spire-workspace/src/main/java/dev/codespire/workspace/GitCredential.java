package dev.codespire.workspace;

import java.util.Objects;

/**
 * Credentials for a git remote. Never rendered into a URL — a URL reaches the log stream — and
 * never into a generated {@code toString()}, which is where an exception message would carry it
 * onto an operator's timeline.
 */
public record GitCredential(String username, String secret) {

    public GitCredential {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(secret, "secret");
    }

    @Override
    public String toString() {
        return "GitCredential[username=" + username + ", secret=***]";
    }
}
