package dev.codespire.orchestrator.repository;

import java.util.UUID;

/** Credentials stay on accounts; a repository input carries selected account ids only. */
public record RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                              Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId) { }
