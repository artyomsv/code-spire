package dev.codespire.contract.context;

/**
 * The credential a worker needs to reach an external context source (Jira today,
 * Confluence next) — the context analog of {@link dev.codespire.contract.scm.ScmCredential}
 * and {@link dev.codespire.contract.llm.LlmCredential}. It travels ONLY as
 * KEK-encrypted ciphertext on the opaque {@code ActionCommand.contextCredential}
 * field of a {@code GatherContext} command — never in cleartext on the bus, never
 * logged. The orchestrator packs it from each enabled source and its enabled account; the worker unpacks it after decrypting with the master keyset.
 *
 * <p>{@code platform} is the account kind; the code reader uses it instead of guessing from the host.
 * {@code type} is the provider type ({@code "jira"}) — the worker uses it to pick
 * the {@link dev.codespire.contract.port.ContextProvider}. {@code authKind} is
 * {@code "basic"} (Jira Cloud: {@code username} is the account email, {@code secret}
 * is an API token) or {@code "bearer"} (self-managed PAT). {@code projectKeys} is the
 * instance's project-key list (e.g. {@code "ACME"}), used to narrow candidate
 * issue keys; blank = accept every well-formed key.
 */
public record ContextCredential(String type, String platform, String baseUrl, String authKind, String username,
                                String secret, String projectKeys) {

    /** Preserve platform when changing a source's allowlist at a wire rebuild site. */
    public ContextCredential withProjectKeys(String keys) {
        return new ContextCredential(type, platform, baseUrl, authKind, username, secret, keys);
    }

    /**
     * The Tink associated-data for a context credential, binding the ciphertext
     * to the PR's workspace so it cannot be replayed against another — a distinct
     * prefix from the SCM and LLM cred AADs. Both the orchestrator (encrypt) and
     * the worker (decrypt) derive it identically.
     */
    public static String aad(String workspace) {
        return "worker-context-cred:" + workspace;
    }
}
