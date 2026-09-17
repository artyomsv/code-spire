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
     */
    record Start(String signInId, String harness, String image, long maxWaitSeconds)
            implements HarnessSignInCommand {

        public Start {
            if (signInId == null || signInId.isBlank()) throw new IllegalArgumentException("A sign-in id is required");
            if (harness == null || harness.isBlank()) throw new IllegalArgumentException("A harness name is required");
            if (image == null || image.isBlank()) throw new IllegalArgumentException("An agent image is required");
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
