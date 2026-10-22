package dev.codespire.publisher;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Selected by a trusted executable, never by anything in the repository or handoff volume.
 *
 * <p>{@code expectedRemoteHead} and {@code mergedBaseTip} come from a land fix's permit (M4 slice 7a): the push
 * goes to the item's existing branch only while the remote still points at the expected head, and paths whose
 * content equals the merged base tip are the base's, not the agent's.
 */
record PublicationPolicy(Mode mode, String head, Instant issuedAt, Instant expiresAt, Clock clock,
                         String expectedRemoteHead, String mergedBaseTip) {
    enum Mode { AUTOMATIC, HELD, PERMITTED }

    static PublicationPolicy automatic() {
        return new PublicationPolicy(Mode.AUTOMATIC, null, null, null, Clock.systemUTC(), null, null);
    }

    static PublicationPolicy held() {
        return new PublicationPolicy(Mode.HELD, null, null, null, Clock.systemUTC(), null, null);
    }

    static PublicationPolicy permitted(String head, Instant issuedAt, Instant expiresAt, Clock clock) {
        if (head == null || !head.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("SPIRE_PERMITTED_HEAD must be a full checkpoint head");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalStateException("The publication permit needs a positive validity window");
        }
        return new PublicationPolicy(Mode.PERMITTED, head, issuedAt, expiresAt,
                java.util.Objects.requireNonNull(clock), null, null);
    }

    /** The same permitted policy, pushing to an existing branch expected at {@code expectedRemoteHead}. */
    PublicationPolicy onExistingBranch(String expectedRemoteHead, String mergedBaseTip) {
        return new PublicationPolicy(mode, head, issuedAt, expiresAt, clock, expectedRemoteHead, mergedBaseTip);
    }

    static PublicationPolicy fromEnv(Mode mode, Map<String, String> env) {
        return switch (mode) {
            case AUTOMATIC -> automatic();
            case HELD -> held();
            case PERMITTED -> {
                try {
                    yield permitted(Env.required(env, "SPIRE_PERMITTED_HEAD"),
                            Instant.parse(Env.required(env, "SPIRE_PERMIT_ISSUED_AT")),
                            Instant.parse(Env.required(env, "SPIRE_PERMIT_EXPIRES_AT")), Clock.systemUTC());
                } catch (DateTimeParseException e) {
                    throw new IllegalStateException("Publication permit timestamps must be UTC instants");
                }
            }
        };
    }

    boolean selects(String candidate) {
        return mode != Mode.PERMITTED || head.equals(candidate);
    }

    boolean validNow() {
        Instant now = clock.instant();
        return mode != Mode.PERMITTED || (!now.isBefore(issuedAt) && now.isBefore(expiresAt));
    }
}
