package dev.codespire.contract.scm;

/**
 * The minimal SCM credential a worker needs to build a per-command client
 * (ADR-015). It travels ONLY as KEK-encrypted ciphertext on the opaque
 * {@code ActionCommand.scmCredential} field — never in cleartext on the bus,
 * never logged. The orchestrator packs it from the provider registry; the
 * worker unpacks it after decrypting with the master keyset.
 *
 * <p>{@code authKind} is {@code "bearer"} (secret is an access token) or
 * {@code "basic"} (secret is a password/app-password paired with {@code username}).
 */
public record ScmCredential(String baseUrl, String authKind, String username,
                            String secret, String botAccountId) {

    /**
     * The Tink associated-data for a worker credential, binding the ciphertext
     * to the PR's workspace so it cannot be replayed against another. Both the
     * orchestrator (encrypt) and the worker (decrypt) derive it identically.
     */
    public static String aad(String workspace) {
        return "worker-cred:" + workspace;
    }
}
