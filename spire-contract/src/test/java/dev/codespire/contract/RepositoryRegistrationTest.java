package dev.codespire.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RepositoryRegistration;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryRegistrationTest {
    @Test void metadataOnlyWireShapeRoundTrips() throws Exception {
        String json = """
                {"type":"RepositoryRegistration","registrationId":"00000000-0000-0000-0000-000000000001","revision":17,"providerType":"TEST-forge",
                 "forgeOrigin":null,"scope":"repo","target":"TEST-group/nested/TEST-repo","enabled":true,"deleted":false,
                 "repositoryId":"00000000-0000-0000-0000-000000000002","eventKind":"FACTORY","sourceId":null}
                """;
        var mapper = new ObjectMapper();
        var snapshot = mapper.readValue(json, RepositoryRegistration.class);
        assertEquals(17, snapshot.revision());
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(snapshot)));
    }
    @Test void legacyMetadataDefaultsToReviewerWithoutInventingRepositoryOrSource() throws Exception {
        String json = """
                {"type":"RepositoryRegistration","registrationId":"00000000-0000-0000-0000-000000000001","revision":17,"providerType":"TEST-forge",
                 "forgeOrigin":null,"scope":"repo","target":"TEST-group/nested/TEST-repo","enabled":true,"deleted":false}
                """;
        RepositoryRegistration snapshot = new ObjectMapper().readValue(json, RepositoryRegistration.class);
        assertEquals(dev.codespire.contract.event.RepositoryEventKind.REVIEWER, snapshot.eventKind());
        assertNull(snapshot.repositoryId());
        assertNull(snapshot.sourceId());
    }
    @Test void rejectsInvalidSnapshotBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryRegistration(UUID.randomUUID(), 0, "TEST-forge", null, "repo", "TEST/repo", true, false));
    }

    @Test void rejectsBlankOriginButAllowsUnknownOrigin() {
        UUID id = UUID.randomUUID();
        for (String origin : new String[] { "", " \t\r\n" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RepositoryRegistration(id, 1, "TEST-forge", origin, "repo", "TEST/repo", true, false));
        }
        assertNull(new RepositoryRegistration(id, 1, "TEST-forge", null, "repo", "TEST/repo", true, false).forgeOrigin());
    }
}
