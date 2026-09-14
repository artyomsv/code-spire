package dev.codespire.contract.event;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worksource.WorkSourceSignal;
import java.util.UUID;

/** Dedicated work ingress. This is deliberately outside IntegrationEvent and review history. */
public record WorkSourceDelivery(UUID repositoryId, UUID sourceId, UUID registrationId, long registrationRevision,
                                 String providerType, String forgeOrigin, RepoRef repo,
                                 String deliveryId, WorkSourceSignal signal) {}
