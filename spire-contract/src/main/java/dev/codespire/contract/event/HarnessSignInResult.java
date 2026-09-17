package dev.codespire.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Objects;

/**
 * What the trusted sign-in unit reports back (M3.5 part F). Rides {@code cs.harness-sign-in-results},
 * keyed by {@code signInId}.
 *
 * <p>Two of these are mid-flight rather than terminal, which {@code RunResult} already does with
 * {@code RunStarted} and {@code RunWorkReady}: the operator cannot act until the unit has told them
 * where to go and what to type, and that happens while the unit is still waiting.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HarnessSignInResult.Prompted.class, name = "Prompted"),
        @JsonSubTypes.Type(value = HarnessSignInResult.Completed.class, name = "Completed"),
        @JsonSubTypes.Type(value = HarnessSignInResult.Failed.class, name = "Failed")
})
public sealed interface HarnessSignInResult {

    String signInId();

    /**
     * The AAD the sealed sign-in is bound to.
     *
     * <p>Bound to the sign-in rather than to the harness or the deployment, for the reason
     * {@code RunCommand} binds a run's credentials to its run id: a ciphertext lifted from one result
     * cannot be replayed as the answer to another sign-in.
     */
    static String sealedAad(String signInId) {
        return "harness-sign-in:" + Objects.requireNonNull(signInId, "signInId");
    }

    /**
     * What the operator must do, and by when.
     *
     * <p>None of these three is a secret. The code authorizes nothing on its own — only the account
     * holder can approve it, and only the unit that started the flow can collect the result — so it is
     * shown on screen on purpose. It is still short-lived and single-use, and the screen says so.
     *
     * @param verificationUri where to go. Reported by the CLI rather than held as a constant here, so a
     *     vendor that moves the page moves it once.
     * @param expiresAt when the code stops working, so the screen can count down rather than leave an
     *     operator typing a dead code.
     */
    record Prompted(String signInId, String verificationUri, String userCode, Instant expiresAt)
            implements HarnessSignInResult {

        public Prompted {
            if (signInId == null || signInId.isBlank()) throw new IllegalArgumentException("A sign-in id is required");
            if (verificationUri == null || !verificationUri.startsWith("https://"))
                throw new IllegalArgumentException("A verification link must be https; an operator is being"
                        + " asked to type their account credentials into whatever this points at");
            if (userCode == null || userCode.isBlank()) throw new IllegalArgumentException("A one-time code is required");
            Objects.requireNonNull(expiresAt, "A code the screen cannot count down is a code someone types too late");
        }
    }

    /**
     * The operator approved it and the unit has a sign-in.
     *
     * @param sealedAuth the bytes the CLI wrote, Tink-sealed under {@link #sealedAad}. Opaque here on
     *     purpose: the fields inside are the vendor's, this build has measured only {@code auth_mode},
     *     and a record that named the rest would be naming fields nobody has seen.
     * @param authMode the one field that HAS been measured, so the orchestrator can refuse a sign-in
     *     that came back as an API key without opening the sealed bytes on the wire path.
     * @param identity what {@code codex login status} printed, already masked by the unit. Display only.
     *     Never an e-mail address: the unit strips anything of that shape before it leaves, because an
     *     address must not be logged or persisted anywhere in this system.
     */
    record Completed(String signInId, String sealedAuth, String authMode, String identity)
            implements HarnessSignInResult {

        public Completed {
            if (signInId == null || signInId.isBlank()) throw new IllegalArgumentException("A sign-in id is required");
            if (sealedAuth == null || sealedAuth.isBlank()) throw new IllegalArgumentException("A completed sign-in carries its sealed bytes");
            if (authMode == null || authMode.isBlank()) throw new IllegalArgumentException("A completed sign-in says which mode it is");
            Objects.requireNonNull(identity, "A sign-in nobody can identify on screen cannot be managed");
        }

        /** Redacts the sealed bytes. A ciphertext in a log is still a credential in a log. */
        @Override
        public String toString() {
            return "Completed[signInId=" + signInId + ", authMode=" + authMode + ", identity=" + identity
                    + ", sealedAuth=***]";
        }
    }

    /**
     * It did not happen, and the screen says why rather than spinning.
     *
     * @param cause a stable reason a screen can map to a sentence, not a vendor message
     */
    record Failed(String signInId, String cause, String detail) implements HarnessSignInResult {

        /** Nobody approved the code before it expired. */
        public static final String EXPIRED = "sign_in_expired";
        /** The operator, or a restart, stopped it. */
        public static final String CANCELLED = "sign_in_cancelled";
        /** The unit could not be started, or died. Distinct from expiry: one is a fault, one is a choice. */
        public static final String UNIT_FAILED = "sign_in_unit_failed";
        /** The CLI signed in, but as an API key rather than a subscription. */
        public static final String WRONG_MODE = "sign_in_wrong_mode";

        public Failed {
            if (signInId == null || signInId.isBlank()) throw new IllegalArgumentException("A sign-in id is required");
            if (cause == null || cause.isBlank()) throw new IllegalArgumentException("A failure names its rule");
            Objects.requireNonNull(detail, "detail may be empty, but not absent");
        }
    }
}
