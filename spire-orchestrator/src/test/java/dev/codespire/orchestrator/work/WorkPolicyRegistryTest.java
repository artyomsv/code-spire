package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkPolicy;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkPolicyRegistryTest extends WorkFixture {
    WorkPolicy.Profile current() { return policies.get(repository).ceiling(); }
    WorkPolicyRegistry.Pin pin() { return new WorkPolicyRegistry.Pin(profile, 1); }

    @Test void anExistingVersionCannotBeOverwritten() throws Exception {
        assertThrows(SQLException.class, () -> execute(
                "UPDATE autonomy_profile_version SET definition='{}'::jsonb WHERE profile_id=?", profile));
        assertEquals("auto", current().modes().get(WorkPolicy.Phase.INTAKE));
    }

    @Test void aNewVersionCannotRenameItsIdentity() {
        WorkPolicy.Profile before = current();
        assertThrows(IllegalArgumentException.class, () -> policies.createVersion(new WorkPolicy.Profile(
                profile, "TEST-renamed", 2, before.precedence(), before.modes())));
        assertEquals(before, current());
    }

    @Test void aNewVersionCannotChangeIdentityPrecedence() {
        WorkPolicy.Profile before = current();
        assertThrows(IllegalArgumentException.class, () -> policies.createVersion(new WorkPolicy.Profile(
                profile, before.name(), 2, before.precedence() + 1, before.modes())));
    }

    @Test void operatorPrecedenceMustBeUnique() {
        UUID other = UUID.randomUUID(); extraProfiles.add(other);
        assertThrows(RuntimeException.class, () -> policies.createVersion(new WorkPolicy.Profile(
                other, "TEST-profile-" + other, 1, current().precedence(), current().modes())));
    }

    @Test void stalePolicyCannotReplaceTheMapping() {
        assertThrows(IllegalArgumentException.class, () -> policies.save(repository,
                new WorkPolicyRegistry.Input(0, pin(), Map.of("TEST-replacement", pin()))));
        assertTrue(policies.get(repository).mappings().containsKey(LABEL));
        assertEquals(1, policies.get(repository).revision());
    }

    @Test void blankLabelsCannotBeSaved() {
        assertThrows(IllegalArgumentException.class, () -> policies.save(repository,
                new WorkPolicyRegistry.Input(1, pin(), Map.of(" ", pin()))));
        assertTrue(policies.get(repository).mappings().containsKey(LABEL));
    }

    @Test void aDeletedProfileCannotAcquireANewVersion() throws Exception {
        WorkPolicy.Profile before = current();
        execute("UPDATE autonomy_profile SET deleted=true WHERE id=?", profile);
        assertThrows(IllegalArgumentException.class, () -> policies.createVersion(new WorkPolicy.Profile(
                profile, before.name(), 2, before.precedence(), before.modes())));
    }

    @Test void aNewVersionLeavesTheExistingMappingPinned() {
        WorkPolicy.Profile before = current();
        policies.createVersion(new WorkPolicy.Profile(profile, before.name(), 2, before.precedence(), Map.of()));
        assertEquals(before, current());
        assertEquals(1, policies.get(repository).mappings().get(LABEL).version());
    }
}
