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
 * content equals the merged base tip are the base's, not the agent's. A land fix's held build carries the same
 * tip, from the run worker's trusted environment, because it gates the base merge first: without it the base's
 * workflow edit is refused before any checkpoint, verify or permit exists.
 *
 * <p>{@code startHead} is the checkpoint a held build started from (M4 slice 3, A2), from the run worker's trusted
 * environment: a checkpoint that does not descend from it rewrote the chain under a plan step, and is refused.
 */
record PublicationPolicy(Mode mode, String head, Instant issuedAt, Instant expiresAt, Clock clock,
                         String expectedRemoteHead, String mergedBaseTip, String startHead) {
    enum Mode { AUTOMATIC, HELD, PERMITTED, READ_ONLY }

    static PublicationPolicy automatic() {
        return new PublicationPolicy(Mode.AUTOMATIC, null, null, null, Clock.systemUTC(), null, null, null);
    }

    /**
     * A specification or plan run (M4 slice 3): it publishes nothing, and its executable holds no write credential,
     * so even a wrong code path here has no secret to push with.
     */
    static PublicationPolicy readOnly() {
        return new PublicationPolicy(Mode.READ_ONLY, null, null, null, Clock.systemUTC(), null, null, null);
    }

    static PublicationPolicy held() {
        return held(null);
    }

    /** A held build that may have merged the base at {@code mergedBaseTip}; null when it merges nothing. */
    static PublicationPolicy held(String mergedBaseTip) {
        return held(mergedBaseTip, null);
    }

    /** A held build that started from the checkpoint {@code startHead}; null when it started from the base. */
    static PublicationPolicy held(String mergedBaseTip, String startHead) {
        if (startHead != null && !startHead.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("SPIRE_START_HEAD must be a full sha");
        }
        return new PublicationPolicy(Mode.HELD, null, null, null, Clock.systemUTC(), null,
                fullShaOrNull(mergedBaseTip), startHead);
    }

    /** RED stub: a held plan step that may finish with no commit. */
    PublicationPolicy allowingEmpty() {
        return this;
    }

    private static String fullShaOrNull(String mergedBaseTip) {
        if (mergedBaseTip != null && !mergedBaseTip.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("SPIRE_MERGED_BASE_TIP must be a full sha");
        }
        return mergedBaseTip;
    }

    static PublicationPolicy permitted(String head, Instant issuedAt, Instant expiresAt, Clock clock) {
        if (head == null || !head.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("SPIRE_PERMITTED_HEAD must be a full checkpoint head");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalStateException("The publication permit needs a positive validity window");
        }
        return new PublicationPolicy(Mode.PERMITTED, head, issuedAt, expiresAt,
                java.util.Objects.requireNonNull(clock), null, null, null);
    }

    /** The same permitted policy, pushing to an existing branch expected at {@code expectedRemoteHead}. */
    PublicationPolicy onExistingBranch(String expectedRemoteHead, String mergedBaseTip) {
        if (mode != Mode.PERMITTED) {
            throw new IllegalStateException("Only a permitted publisher pushes to an existing branch");
        }
        if (expectedRemoteHead == null || !expectedRemoteHead.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("SPIRE_EXPECTED_REMOTE_HEAD must be a full sha");
        }
        return new PublicationPolicy(mode, head, issuedAt, expiresAt, clock, expectedRemoteHead,
                fullShaOrNull(mergedBaseTip), null);
    }

    static PublicationPolicy fromEnv(Mode mode, Map<String, String> env) {
        return switch (mode) {
            case AUTOMATIC -> automatic();
            case READ_ONLY -> readOnly();
            case HELD -> held(optional(env, "SPIRE_MERGED_BASE_TIP"), optional(env, "SPIRE_START_HEAD"));
            case PERMITTED -> {
                try {
                    PublicationPolicy permit = permitted(Env.required(env, "SPIRE_PERMITTED_HEAD"),
                            Instant.parse(Env.required(env, "SPIRE_PERMIT_ISSUED_AT")),
                            Instant.parse(Env.required(env, "SPIRE_PERMIT_EXPIRES_AT")), Clock.systemUTC());
                    String expected = optional(env, "SPIRE_EXPECTED_REMOTE_HEAD");
                    String tip = optional(env, "SPIRE_MERGED_BASE_TIP");
                    if (expected == null && tip != null) {
                        throw new IllegalStateException("SPIRE_MERGED_BASE_TIP needs SPIRE_EXPECTED_REMOTE_HEAD");
                    }
                    yield expected == null ? permit : permit.onExistingBranch(expected, tip);
                } catch (DateTimeParseException e) {
                    throw new IllegalStateException("Publication permit timestamps must be UTC instants");
                }
            }
        };
    }

    private static String optional(Map<String, String> env, String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    boolean selects(String candidate) {
        return mode != Mode.PERMITTED || head.equals(candidate);
    }

    boolean validNow() {
        Instant now = clock.instant();
        return mode != Mode.PERMITTED || (!now.isBefore(issuedAt) && now.isBefore(expiresAt));
    }
}
