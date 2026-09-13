package dev.codespire.contract.event;

import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.contract.scm.RepoRef;
import java.util.Objects;
import java.util.UUID;

/** Verified gateway provenance, or an operator's explicit repository selection. No credentials. */
@com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME, property = "type")
@com.fasterxml.jackson.annotation.JsonTypeName("RepositoryDelivery")
public record RepositoryDelivery(UUID repositoryId, UUID registrationId, long registrationRevision,
                                 String providerType, String forgeOrigin, RepositoryEventKind eventKind,
                                 String deliveryId, IntegrationEvent event) {
    public RepositoryDelivery {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(eventKind, "eventKind");
        Objects.requireNonNull(event, "event");
        if (deliveryId == null || deliveryId.isBlank()) throw new IllegalArgumentException("deliveryId is required");
        if (registrationId == null && repositoryId == null) throw new IllegalArgumentException("Repository or registration is required");
        if (registrationId != null && registrationRevision < 1) throw new IllegalArgumentException("Registration revision is required");
        if (forgeOrigin != null) forgeOrigin = ForgeOrigin.of(forgeOrigin);
    }

    public RepoRef repo() {
        return switch (event) {
            case IntegrationEvent.PullRequestEventReceived e -> e.repo();
            case IntegrationEvent.PullRequestClosed e -> e.repo();
            case IntegrationEvent.ManualCommandReceived e -> e.repo();
            case IntegrationEvent.AuthorReplied e -> e.repo();
            case IntegrationEvent.PushReceived e -> e.repo();
            default -> throw new IllegalArgumentException("Not a repository ingress event");
        };
    }
}
