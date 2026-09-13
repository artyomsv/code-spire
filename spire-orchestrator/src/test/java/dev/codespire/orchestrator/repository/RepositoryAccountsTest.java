package dev.codespire.orchestrator.repository;

import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RepositoryAccountsTest extends RepositoryFixture {
    @Test void reviewerNeverReceivesTheFactoryCredential() {
        UUID factory = account("FACTORY"), reviewer = account("REVIEWER");
        var repository = repositories.create(repository(reviewer, factory));
        assertEquals("TEST-secret-REVIEWER", accounts.resolve(repository.id(), ProviderRole.REVIEWER).orElseThrow().secret());
        assertEquals("TEST-secret-FACTORY", accounts.resolve(repository.id(), ProviderRole.FACTORY).orElseThrow().secret());
        assertEquals(providers.resolve("gitlab", workspace, ProviderRole.REVIEWER), accounts.resolve(repository.id(), ProviderRole.REVIEWER));
        assertEquals(providers.resolve("gitlab", workspace, ProviderRole.FACTORY), accounts.resolve(repository.id(), ProviderRole.FACTORY));
    }

    @Test void rejectsAnAccountFromAnotherOrigin() throws Exception {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        execute("UPDATE scm_provider SET base_url='https://TEST-other.example.test' WHERE id=?", reviewer);
        assertTrue(accounts.resolve(repo.id(), ProviderRole.REVIEWER).isEmpty());
    }

    @Test void disabledAccountCannotServeAnExistingBinding() {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        providers.update(reviewer, input("REVIEWER", origin, false, "TEST-id-REVIEWER"));
        assertTrue(accounts.resolve(repo.id(), ProviderRole.REVIEWER).isEmpty());
        assertEquals("disabled", repositories.get(repo.id()).orElseThrow().reviewer().state());
    }

    @Test void disabledRepositoryCannotResolve() {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        repositories.update(repo.id(), repo.revision(), new RepositoryInput("gitlab", origin, workspace, "TEST-repo", false, reviewer, null));
        assertTrue(accounts.resolve(repo.id(), ProviderRole.REVIEWER).isEmpty());
    }

    @Test void corruptBindingCannotUseAnotherRole() throws Exception {
        UUID factory = account("FACTORY");
        var repo = repositories.create(repository(null, factory));
        execute("UPDATE repository_account SET role='REVIEWER' WHERE repository_id=?", repo.id());
        assertTrue(accounts.resolve(repo.id(), ProviderRole.REVIEWER).isEmpty());
    }

    @Test void corruptBindingCannotUseAnotherKind() throws Exception {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        execute("UPDATE repository SET scm_type='github' WHERE id=?", repo.id());
        assertTrue(accounts.resolve(repo.id(), ProviderRole.REVIEWER).isEmpty());
    }

    @Test void referencedAccountCannotBeRepurposed() {
        UUID reviewer = account("REVIEWER");
        repositories.create(repository(reviewer, null));
        assertThrows(AccountConflict.class, () -> providers.update(reviewer,
                input("REVIEWER", "https://TEST-other.example.test", true, "TEST-id-REVIEWER")));
        assertEquals(origin, providers.resolveById(reviewer).orElseThrow().baseUrl());
    }

    @Test void referencedDeleteNamesTheRepository() {
        UUID reviewer = account("REVIEWER");
        repositories.create(repository(reviewer, null));
        var failure = assertThrows(AccountConflict.class, () -> providers.delete(reviewer));
        assertTrue(failure.getMessage().contains(origin + "/" + workspace + "/TEST-repo"));
        assertTrue(providers.resolveById(reviewer).isPresent());
    }

    @Test void rejectsCrossOriginBindingWithoutLeavingRepository() {
        UUID reviewer = account("REVIEWER", "https://TEST-other.example.test");
        assertThrows(AccountConflict.class, () -> repositories.create(repository(reviewer, null)));
        assertTrue(repositories.list().stream().noneMatch(repo -> repo.workspace().equals(workspace)));
    }

    @Test void rejectsWrongRoleBinding() {
        UUID factory = account("FACTORY");
        assertThrows(AccountConflict.class, () -> repositories.create(repository(factory, null)));
    }

    @Test void missingAccountIsAnActionableConflict() {
        assertThrows(AccountConflict.class, () -> repositories.create(repository(UUID.randomUUID(), null)));
    }

    @Test void rejectsWrongKindBinding() throws Exception {
        UUID reviewer = account("REVIEWER");
        execute("UPDATE scm_provider SET type='github' WHERE id=?", reviewer);
        assertThrows(AccountConflict.class, () -> repositories.create(repository(reviewer, null)));
    }

    @Test void rejectsSameResolvedIdentity() {
        UUID reviewer = account("REVIEWER"), factory = account("FACTORY");
        providers.update(factory, input("FACTORY", origin, true, "TEST-id-REVIEWER"));
        assertThrows(AccountConflict.class, () -> repositories.create(repository(reviewer, factory)));
    }

    @Test void rotationReachesBindingWithoutReassignment() {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        var rotated = input("REVIEWER", origin, true, "TEST-id-REVIEWER");
        providers.update(reviewer, new dev.codespire.orchestrator.provider.ProviderInput(rotated.name(), rotated.type(), origin,
                workspace, "bearer", null, "TEST-rotated", rotated.botAccountId(), true, rotated.authors(), rotated.botUsername(), null, "REVIEWER"));
        assertEquals("TEST-rotated", accounts.resolve(repo.id(), ProviderRole.REVIEWER).orElseThrow().secret());
    }

    @Test void identityCollisionAfterRotationCannotServeEitherRole() {
        UUID reviewer = account("REVIEWER"), factory = account("FACTORY");
        var repo = repositories.create(repository(reviewer, factory));
        providers.update(factory, input("FACTORY", origin, true, "TEST-id-REVIEWER"));
        assertTrue(accounts.resolve(repo.id(), ProviderRole.REVIEWER).isEmpty());
        assertTrue(accounts.resolve(repo.id(), ProviderRole.FACTORY).isEmpty());
        assertEquals("identity-conflict", repositories.get(repo.id()).orElseThrow().factory().state());
    }
}
