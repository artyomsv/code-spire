package dev.codespire.orchestrator.factory;

import dev.codespire.contract.scm.RepositoryPermission;

/** Authorizes the command only. Target, finding, observe-mode and spending checks still apply. */
public final class FixAuthorization {
    private FixAuthorization() {}
    public enum Override { ALLOW, DENY }
    public enum Reason { UNKNOWN_ACTOR, EXPLICIT_DENY, EXPLICIT_ALLOW, CAN_PUSH, CANNOT_PUSH, PERMISSION_UNAVAILABLE, REPOSITORY_UNAVAILABLE }
    public record Decision(boolean allowed, Reason reason, String detail) {}

    public static Decision decide(String actorId, Override override, RepositoryPermission permission) {
        if (actorId == null || actorId.isBlank()) return new Decision(false, Reason.UNKNOWN_ACTOR, "The command has no stable provider actor identity.");
        if (override == Override.DENY) return new Decision(false, Reason.EXPLICIT_DENY, "This person is explicitly denied /fix on this repository.");
        if (override == Override.ALLOW) return new Decision(true, Reason.EXPLICIT_ALLOW, "This person is explicitly allowed /fix on this repository.");
        return switch (permission.state()) {
            case CAN_PUSH -> new Decision(true, Reason.CAN_PUSH, "The person has current repository push access.");
            case CANNOT_PUSH -> new Decision(false, Reason.CANNOT_PUSH, "The person does not have repository push access or an explicit /fix grant.");
            case UNKNOWN -> new Decision(false, Reason.PERMISSION_UNAVAILABLE, permission.detail());
        };
    }
}
