package dev.codespire.contract.command;

import java.util.Objects;

/**
 * What {@link RunCommand.ExecuteRun#scmCredential()} decrypts to: the machine account's login and
 * its token, together. The login travels with the token because the forge needs both to accept a
 * push and the worker must not guess it — a name fixed in the worker would be right for one
 * deployment and silently wrong for the next. Serialised as JSON inside the Tink envelope; never
 * on the wire in the clear.
 */
public record MachineAccountCredential(String username, String secret) {

    public MachineAccountCredential {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(secret, "secret");
        if (username.isBlank() || secret.isBlank()) {
            throw new IllegalArgumentException("a machine account needs both a username and a secret");
        }
    }

    /** Never prints the secret: a record's generated form would put the token in any log line. */
    @Override
    public String toString() {
        return "MachineAccountCredential[username=" + username + ", secret=***]";
    }
}
