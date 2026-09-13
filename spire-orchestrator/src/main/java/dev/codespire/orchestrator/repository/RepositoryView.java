package dev.codespire.orchestrator.repository;

import java.util.UUID;

/** Selected accounts are visible even when disabled; this view never decrypts a credential. */
public record RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace,
                             String slug, boolean enabled, long revision, Account reviewer, Account factory) {
    public record Account(UUID id, String name, String role, String handle, String state) { }
}
