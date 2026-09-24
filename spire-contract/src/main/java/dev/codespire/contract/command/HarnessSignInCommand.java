package dev.codespire.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Objects;

/**
 * An operator signing a harness in, driven by the vendor's own CLI in a trusted unit (M3.5 part F).
 *
 * <p><b>Why this is not a {@code RunCommand}.</b> That hierarchy requires a {@code runId}, and a
 * sign-in is not a run: it has no repository, no workspace, no prompt, no model call and no cost. Its
 * own file already says why the two were split — "a run id behind a method named {@code reviewId()} is
 * a name that lies" — and a sign-in id behind {@code runId()} is the same lie. Keeping it separate also
 * keeps the dispatch path that spends money untouched by a screen.
 *
 * <p>Rides {@code cs.harness-sign-in-commands}, keyed by {@code signInId}.
 *
 * <p><b>The unit this starts is trusted, and the word is load-bearing.</b> It gets no repository, no
 * workspace mount, no prompt and no ticket text — nothing attacker-controlled enters it. That is what
 * makes reading its output safe, and it is the whole difference between this and an agent container,
 * whose output is a prompt-injection surface and is never believed.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HarnessSignInCommand.Start.class, name = "Start"),
        @JsonSubTypes.Type(value = HarnessSignInCommand.Cancel.class, name = "Cancel")
})
public sealed interface HarnessSignInCommand {

    String signInId();

    /**
     * Start the device-authorization sign-in and report back what the operator must do.
     *
     * @param harness which arm is being signed in. Checked against the images this deployment has, so an
     *     unknown name is refused before a container starts rather than after one fails.
     * @param image the agent image whose pinned CLI drives the flow. Explicit rather than derived,
     *     because the CLI version is what the measurements in the design were taken against, and a unit
     *     running a different one is measuring something else.
     * @param maxWaitSeconds how long the unit may wait for the operator. The vendor states a 15-minute
     *     code lifetime; this is the deployment's own ceiling, so a unit cannot outlive the code it is
     *     waiting on and sit holding a container for ever.
     * @param requestedAt when the operator pressed start. The wait is counted from HERE, not from delivery:
     *     the channel replays from its oldest record and the orchestrator re-sends a start nobody picked
     *     up, so a start can arrive late or twice. One whose wait has already run out opens no unit, and
     *     a late one gets only the time left (review of PR #168). Null in a start sent before this existed.
     * @param startWithinSeconds how long after the press a unit may still be OPENED, as distinct from how
     *     long a person may take to approve ({@code maxWaitSeconds}). The orchestrator ends a sign-in that
     *     shows no code a little after this, so a worker must not open one it could only prompt for once
     *     that has happened. Carried rather than configured twice, so the two sides cannot drift. Zero in
     *     a start sent before this existed, which then opens nothing.
     */
    record Start(String signInId, String harness, String image, long maxWaitSeconds, java.time.Instant requestedAt,
                 long startWithinSeconds)
            implements HarnessSignInCommand {

        public Start(String signInId, String harness, String image, long maxWaitSeconds) {
            this(signInId, harness, image, maxWaitSeconds, null, 0);
        }

        /** When the code must be on screen by; after this the orchestrator ends the row. Null if unknown. */
        public java.time.Instant promptDeadline() {
            return requestedAt == null ? null : requestedAt.plusSeconds(startWithinSeconds);
        }

        /** When the person's time to approve runs out, counted from the press. Null if unknown. */
        public java.time.Instant approvalDeadline() {
            return requestedAt == null ? null : requestedAt.plusSeconds(maxWaitSeconds);
        }

        /**
         * Whether a unit opened now could still show its code in time: the code must be on screen by the
         * end of the start window, and the unit may take {@code toPrompt} to print it.
         */
        public boolean mayOpenAt(java.time.Instant now, java.time.Duration toPrompt) {
            if (requestedAt == null || startWithinSeconds <= 0) return false;
            return !now.plus(toPrompt).isAfter(requestedAt.plusSeconds(startWithinSeconds));
        }

        /** How long the unit may still wait, counted from the request; the full wait when that is unknown. */
        public java.time.Duration remainingWait(java.time.Instant now) {
            java.time.Duration full = java.time.Duration.ofSeconds(maxWaitSeconds);
            if (requestedAt == null) return full;
            java.time.Duration left = java.time.Duration.between(now, requestedAt.plus(full));
            return left.isNegative() ? java.time.Duration.ZERO : (left.compareTo(full) > 0 ? full : left);
        }

        public Start {
            if (signInId == null || signInId.isBlank()) throw new IllegalArgumentException("A sign-in id is required");
            if (harness == null || harness.isBlank()) throw new IllegalArgumentException("A harness name is required");
            if (image == null || image.isBlank()) throw new IllegalArgumentException("An agent image is required");
            if (startWithinSeconds < 0 || startWithinSeconds > maxWaitSeconds) throw new IllegalArgumentException(
                    "A start window of " + startWithinSeconds + " seconds must lie within the wait of " + maxWaitSeconds);
            if (maxWaitSeconds <= 0) throw new IllegalArgumentException(
                    "A sign-in wait of " + maxWaitSeconds + " seconds would end the unit before the operator"
                            + " could read the code; the wait is a ceiling, not a switch");
        }
    }

    /** The operator gave up, or the code expired. Stops the unit rather than waiting out the ceiling. */
    record Cancel(String signInId, String reason) implements HarnessSignInCommand {

        public Cancel {
            if (signInId == null || signInId.isBlank()) throw new IllegalArgumentException("A sign-in id is required");
            Objects.requireNonNull(reason, "A cancel says why, so the screen can too");
        }
    }
}
