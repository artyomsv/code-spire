package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.RepositoryDelivery;
import dev.codespire.orchestrator.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DevSimulatorRepositoryTest {
    private final UUID id = UUID.randomUUID();
    private final List<RepositoryDelivery> sent = new ArrayList<>();
    private final DevSimulatorResource resource = new DevSimulatorResource();
    private String namespace = "TEST-simulator";

    DevSimulatorRepositoryTest() {
        resource.stubScm = true;
        resource.repositories = new RepositoryRegistry() {
            @Override public Optional<RepositoryView> get(UUID requested) {
                return Optional.of(new RepositoryView(id, "gitlab", "https://TEST-forge.example.test", namespace,
                        "TEST-repo", true, 1, null, null));
            }
        };
        resource.integration = new RepositoryDeliveryEmitter() {
            @Override public void send(RepositoryDelivery delivery) { sent.add(delivery); }
        };
    }

    @Test void simulationCarriesItsSelectedRepositoryAndCannotUseRealNamespaces() {
        resource.simulate(id);
        assertEquals(id, sent.getFirst().repositoryId());
        assertEquals("TEST-simulator", sent.getFirst().repo().workspace());
        sent.clear(); namespace = "unmarked";
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> resource.simulate(id));
        assertTrue(sent.isEmpty());
    }

    @Test void simulationRequiresAnExplicitRepository() {
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> resource.simulate(null));
        assertTrue(sent.isEmpty());
        resource.simulate(id); assertEquals(1, sent.size());
    }

    @Test void simulationRequiresStubMode() {
        resource.stubScm = false;
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> resource.simulate(id));
        assertTrue(sent.isEmpty());
        resource.stubScm = true; resource.simulate(id); assertEquals(1, sent.size());
    }
}
