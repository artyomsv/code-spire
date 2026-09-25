package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.HarnessInvocation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;

/**
 * Unpacks the opaque, KEK-encrypted credentials a command carries into what each container is
 * given.
 *
 * <p><b>The split between a READ and a WRITE credential is the point, not an optimisation.</b> The
 * init container clones, which needs read; the publisher pushes, which needs write; the agent needs
 * neither and gets neither. Handing one token to all three would make ADR-039's containment
 * theatre — the agent could simply push.
 *
 * <p>Decryption happens here and nowhere else in the worker, with the AAD the orchestrator's
 * {@code RunCredentials} bound the ciphertext to: the run id and the slot. A value lifted from one
 * run's record — or from {@code dlq_entry.payload}, which is where an unwrapped token would have
 * ended up — cannot be presented under another run, and the two slots cannot be swapped. The
 * machine account's login arrives inside the envelope; this class names no account and no vendor
 * variable — the harness arm decides what its own process calls the key.
 */
@ApplicationScoped
public class Credentials {

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

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

    public Scm scm(String runId, String packed) {
        if (packed == null || packed.isBlank()) {
            throw new IllegalArgumentException("a run needs an SCM credential; none was packed");
        }
        String json = encryption.decryptString(packed, RunCommand.scmCredentialAad(runId));
        MachineAccountCredential account;
        try {
            account = mapper.readValue(json, MachineAccountCredential.class);
        } catch (JsonProcessingException e) {
            // Not chained with the text: the plaintext is the credential.
            throw new IllegalArgumentException("the SCM credential for " + runId + " decrypted to something "
                    + "that is not a machine-account credential");
        }
        return new Scm(account.username(), account.secret(), account.username(), account.secret());
    }

    /**
     * The harness credential, under the SPI's neutral key. The arm's {@code environment()} maps it
     * to whatever its process reads — the vendor's variable name is the arm's knowledge, not this
     * module's — and {@code EnvironmentPolicy} still screens the result.
     */
    public Map<String, String> harnessEnv(String runId, String packed) {
        if (packed == null || packed.isBlank()) {
            return Map.of();
        }
        return Map.of(HarnessInvocation.CREDENTIAL,
                encryption.decryptString(packed, RunCommand.harnessCredentialAad(runId)));
    }

    /**
     * The same credential, handed over under {@link HarnessInvocation#SIGN_IN} when it is a sign-in file.
     * It goes through {@link #harnessEnv(String, String)} rather than decrypting again, so that method
     * stays the one a test fake overrides: a second decrypting overload let the scrub path bypass every
     * fake and leave the key unscrubbed.
     */
    public Map<String, String> harnessEnv(String runId, String packed, boolean signIn) {
        Map<String, String> env = harnessEnv(runId, packed);
        if (!signIn || env.isEmpty()) return env;
        return Map.of(HarnessInvocation.SIGN_IN, env.values().iterator().next());
    }

    /**
     * Everything in a sign-in file that must never reach a log: the file itself, and every text value in
     * it long enough to be a token or an account id. A tool that echoes one token prints that token, not
     * the whole file, so scrubbing the file as one string would miss exactly what leaks.
     */
    public static java.util.List<String> signInSecrets(String file) {
        java.util.List<String> secrets = new java.util.ArrayList<>();
        secrets.add(file);
        try {
            collect(JSON.readTree(file), secrets);
        } catch (JsonProcessingException unreadable) {
            // The whole file is still scrubbed; nothing inside it can be named.
        }
        return secrets;
    }

    private static void collect(com.fasterxml.jackson.databind.JsonNode node, java.util.List<String> secrets) {
        if (node.isTextual() && node.asText().length() >= SHORTEST_SCRUBBED_VALUE) secrets.add(node.asText());
        node.forEach(child -> collect(child, secrets));
    }

    /** Short enough to cover an account id, long enough to leave a word like "chatgpt" alone. */
    private static final int SHORTEST_SCRUBBED_VALUE = 16;

    /** For the static helper above; it reads a document and never serialises one. */
    private static final ObjectMapper JSON = new ObjectMapper();
}
