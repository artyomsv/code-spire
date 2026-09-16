package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.worksource.WorkIssueLocation;
import dev.codespire.worksource.WorkIssueRef;
import dev.codespire.worksource.WorkSourceType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3.5 added two fields to a stored preparation's JSON: an artifact {@code origin} and a
 * {@code bindingVersion}. Every preparation an M3 deployment already wrote has neither.
 *
 * <p>A gate stores {@code preparation.binding()} and answering it compares that stored hash against a
 * RECOMPUTED one, so a decode that produced any other hash would supersede every open decision and
 * invalidate every held run — silently, and everywhere at once. The contract module proves the hash is
 * stable when the version arrives as 0; this proves the PRODUCTION codec actually delivers 0, rather
 * than refusing the missing field or defaulting it elsewhere. The two halves are in different modules
 * because the contract module has no JSON databind, by build rule.
 */
@QuarkusTest
class WorkPreparationWireCompatibilityTest {

    @Inject ObjectMapper mapper;

    /** The exact shape M3 wrote: no origin, no storedId, no bindingVersion. */
    private static final String M3_JSON = """
            {"specification":{"location":{"ref":{"type":"GITHUB","origin":"https://tracker.example.test",
            "projectId":"TEST-project","issueId":"71"},"issueKey":"71","link":"https://tracker.example.test/71"},
            "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
            "plan":{"location":{"ref":{"type":"GITHUB","origin":"https://tracker.example.test",
            "projectId":"TEST-project","issueId":"72"},"issueKey":"72","link":"https://tracker.example.test/72"},
            "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
            "baseBranch":"main","baseCommit":"cccccccccccccccccccccccccccccccccccccccc",
            "harness":"codex","model":"TEST-model","registeredBy":"TEST-operator"}
            """;

    private static WorkIssueLocation ticket(String id) {
        return new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB, "https://tracker.example.test", "TEST-project", id),
                id, URI.create("https://tracker.example.test/" + id));
    }

    private static WorkPreparation asRegisteredUnderM3() {
        return new WorkPreparation(new WorkPreparation.Artifact(ticket("71"), "a".repeat(64)),
                new WorkPreparation.Artifact(ticket("72"), "b".repeat(64)),
                "main", "c".repeat(40), "codex", "TEST-model", "TEST-operator");
    }

    @Test
    void aPreparationWrittenBeforeM35KeepsItsExactApprovalHash() throws Exception {
        WorkPreparation decoded = mapper.readValue(M3_JSON, WorkPreparation.class);

        assertEquals(WorkPreparation.TRACKER_BINDING, decoded.bindingVersion(), "an absent version is version 1");
        assertEquals(WorkPreparation.Origin.TRACKER, decoded.specification().origin());
        assertNull(decoded.specification().storedId());
        assertEquals(asRegisteredUnderM3().binding(), decoded.binding(),
                "the hash an open gate stores must survive the fields M3.5 added");
    }

    /** And the new fields survive their own round trip, or a composed approval could not be answered. */
    @Test
    void aComposedPreparationSurvivesTheRoundTripWithItsStoredIdentity() throws Exception {
        java.util.UUID specId = java.util.UUID.randomUUID(), planId = java.util.UUID.randomUUID();
        WorkPreparation composed = new WorkPreparation(
                new WorkPreparation.Artifact(ticket("71"), "a".repeat(64), WorkPreparation.Origin.STORED, specId),
                new WorkPreparation.Artifact(ticket("72"), "b".repeat(64), WorkPreparation.Origin.STORED, planId),
                "main", "c".repeat(40), "codex", "TEST-model", "system", WorkPreparation.STORED_BINDING);

        WorkPreparation decoded = mapper.readValue(mapper.writeValueAsString(composed), WorkPreparation.class);

        assertEquals(WorkPreparation.STORED_BINDING, decoded.bindingVersion());
        assertEquals(specId, decoded.specification().storedId());
        assertEquals(composed.binding(), decoded.binding());
        assertNotEquals(asRegisteredUnderM3().binding(), decoded.binding(),
                "stored bytes and live tickets are not the same approval");
    }
}
