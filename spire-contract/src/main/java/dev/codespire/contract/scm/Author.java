package dev.codespire.contract.scm;

/**
 * Provider-neutral user identity (SCM-MAPPING.md §1).
 *
 * <p>{@code providerUserId} is the STABLE key (Bitbucket {@code account_id},
 * GitHub/GitLab numeric {@code id}, Bitbucket DC {@code id}); {@code username}
 * is mutable and must never be used as a key. {@code email} is optional (only
 * Bitbucket Data Center exposes it) and must never be logged or persisted.
 */
public record Author(String providerUserId, String username, String displayName, String email) {

    public static Author of(String providerUserId, String username, String displayName) {
        return new Author(providerUserId, username, displayName, null);
    }
}
