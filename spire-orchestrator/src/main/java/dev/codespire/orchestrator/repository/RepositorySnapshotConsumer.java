package dev.codespire.orchestrator.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RepositoryRegistration;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** A failed reconciliation is nacked; it must not acknowledge a snapshot whose mapping was lost. */
@ApplicationScoped
public class RepositorySnapshotConsumer {
    @Inject ObjectMapper mapper;
    @Inject RepositoryMigrationBridge bridge;

    @Incoming("registry-in") @Blocking
    public void on(String payload) throws JsonProcessingException {
        bridge.apply(mapper.readValue(payload, RepositoryRegistration.class));
    }
}
