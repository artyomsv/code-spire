package dev.codespire.runworker;

import java.util.Map;
import java.util.Objects;

/**
 * Unpacks the opaque, KEK-encrypted credentials a command carries into the environment each
 * container is given.
 *
 * <p><b>The split between a READ and a WRITE credential is the point, not an optimisation.</b> The
 * init container clones, which needs read; the publisher pushes, which needs write; the agent needs
 * neither and gets neither. Handing one token to all three would make ADR-038's containment
 * theatre — the agent could simply push.
 */
public final class Credentials {

    private Credentials() {
    }

    /**
     * A machine-account SCM credential, split by what each container may do with it.
     *
     * <p>M0 carries one token and uses it for both directions; the read/write distinction is
     * expressed here so the call sites are already correct when a deployment issues two. That is
     * deliberate and is recorded rather than hidden: a design where the split exists only in a
     * future version is a design where nobody notices it never arrived.
     */
    public record Scm(String readUsername, String readSecret,
                      String writeUsername, String writeSecret) {

        public Scm {
            Objects.requireNonNull(readUsername, "readUsername");
            Objects.requireNonNull(readSecret, "readSecret");
            Objects.requireNonNull(writeUsername, "writeUsername");
            Objects.requireNonNull(writeSecret, "writeSecret");
        }

        /** Never prints a secret: this record is one {@code log.info} away from a credential leak. */
        @Override
        public String toString() {
            return "Scm[readUsername=" + readUsername + ", writeUsername=" + writeUsername
                    + ", secrets=***]";
        }
    }

    public static Scm scm(String packed) {
        if (packed == null || packed.isBlank()) {
            throw new IllegalArgumentException("a run needs an SCM credential; none was packed");
        }
        // M0: the orchestrator packs one machine-account token. Decryption lands with the
        // orchestrator side; this call site already distinguishes the two uses.
        return new Scm(MACHINE_ACCOUNT, packed, MACHINE_ACCOUNT, packed);
    }

    /** The name a forge expects beside a token. */
    private static final String MACHINE_ACCOUNT = "spire-bot";

    /**
     * The harness credential as environment entries.
     *
     * <p>Passed through {@code EnvironmentPolicy} by the adapter that receives it, so a name that
     * would relocate the harness's config or redirect its endpoint is refused there rather than
     * silently honoured here.
     */
    public static Map<String, String> harnessEnv(String packed) {
        if (packed == null || packed.isBlank()) {
            return Map.of();
        }
        return Map.of("OPENAI_API_KEY", packed);
    }
}
