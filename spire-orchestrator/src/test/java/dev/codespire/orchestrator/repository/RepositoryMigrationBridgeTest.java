package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.RepositoryRegistration;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RepositoryMigrationBridgeTest extends RepositoryFixture {
    @Inject RepositoryHistoryBridge histories;
    @Inject dev.codespire.orchestrator.attention.AttentionQueries attention;
    RepositoryRegistration registration(UUID id, long revision) {
        return new RepositoryRegistration(id, revision, "gitlab", origin, "repo", workspace + "/TEST-repo", true, false);
    }
    RepositoryView migrated() {
        return repositories.list().stream().filter(repo -> repo.workspace().equals(workspace)).findFirst().orElseThrow();
    }

    @Test void replaysGatewaySnapshotWithoutDuplicateBindings() throws Exception {
        UUID reviewer = account("REVIEWER"), factory = account("FACTORY");
        snapshotAccounts();
        var snapshot = registration(UUID.randomUUID(), 1);
        bridge.apply(snapshot);
        var repo = migrated();
        assertEquals(reviewer, repo.reviewer().id()); assertEquals(factory, repo.factory().id());
        assertEquals(providers.resolve("gitlab", workspace, ProviderRole.REVIEWER), accounts.resolve(repo.id(), ProviderRole.REVIEWER));
        assertEquals(providers.resolve("gitlab", workspace, ProviderRole.FACTORY), accounts.resolve(repo.id(), ProviderRole.FACTORY));
        // State is in SQL: a fresh CDI-free instance simulates recreation after restart.
        var restarted = new RepositoryMigrationBridge();
        restarted.dataSource = dataSource; restarted.repositories = repositories; restarted.bindings = new RepositoryBindings();
        restarted.apply(snapshot);
        String originalWorkspace = workspace;
        UUID rebound;
        try { workspace += "/TEST-replacement"; rebound = account("REVIEWER"); }
        finally { workspace = originalWorkspace; }
        repositories.update(repo.id(), repo.revision(), repository(rebound, factory));
        bridge.apply(registration(snapshot.registrationId(), 2));
        bridge.apply(registration(UUID.randomUUID(), 1));
        assertEquals(rebound, migrated().reviewer().id(), "gateway replay must not restore the operator-replaced binding");
        assertEquals(1, repositories.list().stream().filter(row -> row.workspace().equals(workspace)).count());
    }

    @Test void leavesConflictingOriginsPending() throws Exception {
        account("REVIEWER"); account("FACTORY", "https://TEST-other.example.test"); snapshotAccounts();
        var snapshot = registration(UUID.randomUUID(), 1);
        bridge.apply(snapshot);
        var pending = mappings.pending().stream().filter(row -> row.registrationId().equals(snapshot.registrationId())).findFirst().orElseThrow();
        assertEquals("conflicting_forge_origins", pending.problem());
        assertTrue(repositories.list().stream().noneMatch(row -> row.workspace().equals(workspace)));
        var row = attention.collect().stream().filter(value -> snapshot.registrationId().toString().equals(value.subject())).findFirst();
        assertTrue(row.isPresent(), "the pending mapping must reach the operator attention panel");
        assertTrue(row.orElseThrow().message().contains(snapshot.target()));
        assertTrue(row.orElseThrow().message().contains(origin));
        assertTrue(row.orElseThrow().action().contains(snapshot.registrationId().toString()));
    }

    @Test void missingLegacyAccountStaysVisibleAndCanBeLinked() {
        var snapshot = registration(UUID.randomUUID(), 1);
        bridge.apply(snapshot);
        var repo = repositories.create(repository(null, null));
        mappings.link(snapshot.registrationId(), 1, repo.id());
        bridge.apply(registration(snapshot.registrationId(), 2));
        assertTrue(mappings.pending().stream().noneMatch(row -> row.registrationId().equals(snapshot.registrationId())));
        assertEquals(repo.id(), migrated().id());
    }

    @Test void staleSnapshotCannotResurrectDeletedRegistration() {
        var snapshot = registration(UUID.randomUUID(), 1);
        bridge.apply(new RepositoryRegistration(snapshot.registrationId(), 2, "gitlab", null, "repo", snapshot.target(), false, true));
        bridge.apply(snapshot);
        assertTrue(mappings.pending().stream().noneMatch(row -> row.registrationId().equals(snapshot.registrationId())));
    }

    @Test void mappingRefusesAnotherRepositoryPath() {
        var snapshot = registration(UUID.randomUUID(), 1);
        bridge.apply(snapshot);
        var wrong = repositories.create(new RepositoryInput("gitlab", origin, workspace, "TEST-wrong", true, null, null));
        assertThrows(AccountConflict.class, () -> mappings.link(snapshot.registrationId(), 1, wrong.id()));
    }

    @Test void mappingRefusesStaleRegistrationRevision() {
        var snapshot = registration(UUID.randomUUID(), 2);
        bridge.apply(snapshot);
        var repo = repositories.create(repository(null, null));
        assertThrows(AccountConflict.class, () -> mappings.link(snapshot.registrationId(), 1, repo.id()));
    }

    @Test void sameBotIdentityBecomesRepairableInsteadOfPoisoningTheConsumer() throws Exception {
        account("REVIEWER"); UUID factory = account("FACTORY");
        providers.update(factory, input("FACTORY", origin, true, "TEST-id-REVIEWER")); snapshotAccounts();
        var snapshot = registration(UUID.randomUUID(), 1);
        bridge.apply(snapshot);
        assertEquals("legacy_binding_invalid", mappings.pending().stream().filter(row -> row.registrationId().equals(snapshot.registrationId()))
                .findFirst().orElseThrow().problem());
        assertTrue(repositories.list().stream().noneMatch(row -> row.workspace().equals(workspace)));
    }

    @Test void orgHistoryCreatesOnlyTheRepositoryActuallyObserved() throws Exception {
        account("REVIEWER"); account("FACTORY"); snapshotAccounts();
        UUID orgRegistration = UUID.randomUUID(); createdRegistrations.add(orgRegistration);
        bridge.apply(new RepositoryRegistration(orgRegistration, 1, "gitlab", origin, "org", workspace.split("/")[0], true, false));
        assertTrue(repositories.list().stream().noneMatch(row -> row.workspace().equals(workspace)));
        var history = new RepositoryHistoryBridge.History("gitlab", workspace, "TEST-repo");
        UUID review = UUID.randomUUID();
        execute("INSERT INTO review_status (review_id,workspace,slug,pr_id,status,provider_type,html_url) VALUES (?,?,?,1,'completed','gitlab',?)",
                review, workspace, "TEST-repo", origin + "/" + workspace + "/TEST-repo/-/merge_requests/1");
        String run = "run::gitlab:" + workspace + "/TEST-repo:TEST-history:1";
        execute("""
                INSERT INTO factory_run (run_id,provider_type,workspace,slug,subject,attempt,status,harness,model,
                    base_branch,base_commit,branch,ended_at) VALUES (?,'gitlab',?,?,'TEST-history',1,'succeeded',
                    'TEST-harness','TEST-model','TEST-main','TEST-sha','TEST-branch',now())
                """, run, workspace, "TEST-repo");
        histories.importHistory(history);
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("SELECT repository_id FROM review_status WHERE review_id=?")) {
            ps.setString(1, review.toString());
            try (var rows = ps.executeQuery()) { assertTrue(rows.next()); assertEquals(migrated().id(), rows.getObject(1, UUID.class)); }
        }
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("SELECT repository_id FROM factory_run WHERE run_id=?")) {
            ps.setString(1, run);
            try (var rows = ps.executeQuery()) { assertTrue(rows.next()); assertEquals(migrated().id(), rows.getObject(1, UUID.class)); }
        }
        histories.importHistory(history);
        assertEquals(1, repositories.list().stream().filter(row -> row.workspace().equals(workspace)).count());
    }

    @Test void unknownRegistrationOriginCannotInheritWorkspaceCredentials() throws Exception {
        account("REVIEWER"); snapshotAccounts();
        UUID id = UUID.randomUUID();
        bridge.apply(new RepositoryRegistration(id, 1, "gitlab", null, "repo", workspace + "/TEST-repo", true, false));
        var pending = mappings.pending().stream().filter(row -> row.registrationId().equals(id)).findFirst();
        assertTrue(pending.isPresent(), "unknown origin must remain explicitly pending");
        assertEquals("registration_origin_unknown", pending.orElseThrow().problem());
        assertTrue(repositories.list().stream().noneMatch(row -> row.workspace().equals(workspace)));
    }

    @Test void registrationFromAnotherHostCannotInheritWorkspaceCredentials() throws Exception {
        account("REVIEWER"); snapshotAccounts();
        UUID id = UUID.randomUUID();
        bridge.apply(new RepositoryRegistration(id, 1, "gitlab", "https://TEST-other.example.test", "repo", workspace + "/TEST-repo", true, false));
        var pending = mappings.pending().stream().filter(row -> row.registrationId().equals(id)).findFirst();
        assertTrue(pending.isPresent(), "mismatched origin must remain explicitly pending");
        assertEquals("registration_origin_mismatch", pending.orElseThrow().problem());
        assertTrue(repositories.list().stream().noneMatch(row -> row.workspace().equals(workspace)));
    }

    @Test void historyFromAnotherHostCannotInheritWorkspaceCredentials() throws Exception {
        account("REVIEWER"); snapshotAccounts();
        execute("INSERT INTO review_status (review_id,workspace,slug,pr_id,status,provider_type,html_url) VALUES (?,?,?,1,'completed','gitlab',?)",
                UUID.randomUUID().toString(), workspace, "TEST-repo", "https://TEST-other.example.test/" + workspace + "/TEST-repo/-/merge_requests/1");
        histories.importHistory(new RepositoryHistoryBridge.History("gitlab", workspace, "TEST-repo"));
        var pending = mappings.pending().stream().filter(row -> row.target().equals(workspace + "/TEST-repo")).findFirst();
        assertTrue(pending.isPresent());
        assertEquals("registration_origin_mismatch", pending.orElseThrow().problem());
        assertTrue(repositories.list().stream().noneMatch(row -> row.workspace().equals(workspace)));
    }
}
