package dev.codespire.orchestrator.ingress;

import dev.codespire.orchestrator.factory.MachineAccounts;
import dev.codespire.orchestrator.factory.RunResource;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.RepositoryView;
import jakarta.ws.rs.BadRequestException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Request identity must be checked before credentials, even when a subsequent lookup could succeed. */
class RepositoryRequestIdentityTest {
    private final UUID id = UUID.randomUUID();
    private final RepositoryView repository = new RepositoryView(id, "gitlab", "https://forge.example.test",
            "TEST-group", "TEST-repo", true, 1, null, null);
    private final RepositoryRegistry registry = new RepositoryRegistry() {
        @Override public Optional<RepositoryView> get(UUID ignored) { return Optional.of(repository); }
    };

    @Test void manualRequiresExplicitRepositoryIdentity() {
        ManualRegisterResource resource = manual();
        assertThrows(CredentialBoundary.class, () -> resource.register(manualRequest(id, null, null, null)));
        assertThrows(BadRequestException.class, () -> resource.register(manualRequest(null, null, null, null)));
    }

    @Test void manualRejectsContradictoryCoordinates() {
        ManualRegisterResource resource = manual();
        assertThrows(CredentialBoundary.class, () -> resource.register(manualRequest(id, null, null, null)));
        assertThrows(BadRequestException.class, () -> resource.register(manualRequest(id, "TEST-other", null, null)));
        assertThrows(BadRequestException.class, () -> resource.register(manualRequest(id, null, "TEST-other", null)));
        assertThrows(BadRequestException.class, () -> resource.register(manualRequest(id, null, null, "github")));
    }

    @Test void runRequiresExplicitRepositoryIdentity() throws Exception {
        RunResource resource = run();
        assertThrows(CredentialBoundary.class, () -> resource.dispatch(runRequest(id, null, null, null)));
        assertThrows(BadRequestException.class, () -> resource.dispatch(runRequest(null, null, null, null)));
    }

    @Test void runRejectsContradictoryCoordinates() throws Exception {
        RunResource resource = run();
        assertThrows(CredentialBoundary.class, () -> resource.dispatch(runRequest(id, null, null, null)));
        assertThrows(BadRequestException.class, () -> resource.dispatch(runRequest(id, "TEST-other", null, null)));
        assertThrows(BadRequestException.class, () -> resource.dispatch(runRequest(id, null, "TEST-other", null)));
        assertThrows(BadRequestException.class, () -> resource.dispatch(runRequest(id, null, null, "github")));
    }

    private ManualRegisterResource manual() {
        ManualRegisterResource resource = new ManualRegisterResource();
        resource.repositories = registry;
        resource.projection = new ReviewProjection() {
            @Override public boolean archived(String review) { return false; }
            @Override public boolean registered(String review) { return false; }
        };
        resource.accounts = new RepositoryAccounts() {
            @Override public Optional<ScmProvider> resolve(UUID selected, ProviderRole role) {
                assertEquals(id, selected); assertEquals(ProviderRole.REVIEWER, role);
                throw new CredentialBoundary();
            }
        };
        return resource;
    }

    private RunResource run() throws Exception {
        RunResource resource = new RunResource();
        set(resource, "repositories", registry);
        set(resource, "config", java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{dev.codespire.orchestrator.factory.FactoryConfig.class}, (proxy, method, args) -> {
                    if (method.getName().equals("agentImage")) return java.util.Map.of("codex", "TEST-agent:latest");
                    throw new AssertionError("Unexpected configuration read: " + method.getName());
                }));
        set(resource, "machineAccounts", new MachineAccounts() {
            @Override public Optional<ScmProvider> resolve(UUID selected) {
                assertEquals(id, selected); throw new CredentialBoundary();
            }
        });
        return resource;
    }

    private ManualRegisterResource.RegisterRequest manualRequest(UUID selected, String workspace, String slug, String kind) {
        return new ManualRegisterResource.RegisterRequest(null, workspace, slug, 7L, kind, selected);
    }
    private RunResource.DispatchRequest runRequest(UUID selected, String workspace, String slug, String kind) {
        return new RunResource.DispatchRequest(workspace, slug, kind, "main",
                "0123456789abcdef0123456789abcdef01234567", "TEST fix", "codex", "TEST-model", "TEST-subject", null, selected);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static final class CredentialBoundary extends Error { }
}
