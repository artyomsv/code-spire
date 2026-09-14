package dev.codespire.orchestrator.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** A failed reconciliation is nacked; it must not acknowledge a snapshot whose mapping was lost. */
@ApplicationScoped
public class RepositorySnapshotConsumer {
    @Inject RepositoryMigrationBridge bridge;

    @Incoming("registry-in") @Blocking
    public void on(String payload) throws JsonProcessingException {
        bridge.applyLegacyPayload(payload);
    }
}
