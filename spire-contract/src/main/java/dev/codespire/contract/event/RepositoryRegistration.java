package dev.codespire.contract.event;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** Non-secret gateway snapshot on cs.registry-integration, keyed by registrationId, never reviewId. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonTypeName("RepositoryRegistration")
public record RepositoryRegistration(UUID registrationId, long revision, String providerType,
                                     String forgeOrigin, String scope, String target,
                                     boolean enabled, boolean deleted) {
    public RepositoryRegistration {
        if (registrationId == null || revision < 1 || providerType == null || providerType.isBlank()
                || (forgeOrigin != null && forgeOrigin.isBlank())
                || (!"repo".equals(scope) && !"org".equals(scope)) || target == null || target.isBlank()) {
            throw new IllegalArgumentException("Invalid repository registration snapshot");
        }
    }
}
