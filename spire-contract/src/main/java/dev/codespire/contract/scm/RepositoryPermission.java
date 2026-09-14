package dev.codespire.contract.scm;

/** Current general code-write access; never a promise that a protected branch accepts a push. */
public record RepositoryPermission(State state, String detail) {
    public enum State { CAN_PUSH, CANNOT_PUSH, UNKNOWN }
    public static RepositoryPermission unknown(String detail) { return new RepositoryPermission(State.UNKNOWN, detail); }
}
