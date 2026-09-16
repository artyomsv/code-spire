package dev.codespire.orchestrator.work;

import com.github.tomakehurst.wiremock.client.WireMock;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.BuildDefaults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One ticket, one label, and the system prepares the task itself (M3.5 part C).
 *
 * <p>What this replaces: a specification ticket, a plan ticket, hand-written JSON inside it, a branch,
 * forty hex characters of commit, a harness name, a model name and two buttons — per item. The operator
 * met that list on their first live test and stopped.
 *
 * <p>The tests that matter here are the ones about what an approval BINDS. The composed texts are
 * stored, so editing the ticket afterwards cannot change what was approved, and a second preparation
 * writes new rows rather than rewriting the ones an open decision already names.
 */
@QuarkusTest
@TestSecurity(user = "TEST-prepared-admin", roles = "spire-admin")
class WorkPreparationSweepTest extends WorkPreparedFixture {

    @Inject WorkPreparationSweep sweep;
    @Inject BuildDefaults defaults;
    @Inject WorkItemArtifacts stored;

    @BeforeEach
    void buildSetup() {
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/branches/main"))
                .willReturn(okJson("{\"name\":\"main\",\"commit\":{\"sha\":\"" + "d".repeat(40) + "\"}}")));
        defaults.save(repository, new BuildDefaults.Input(0, "main", "codex", model), "TEST-prepared-admin");
    }

    private long attempts(String id) throws Exception {
        return count("SELECT count(*) FROM work_item_preparation_attempt WHERE work_item_id=?", id);
    }

    private String reason(String id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT reason FROM work_item_preparation_attempt WHERE work_item_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    @Test
    void oneTicketBecomesAPreparedTaskWithNoTypingAtAll() throws Exception {
        String id = admit("assisted", 80);
        assertNull(store.load(id).preparation(), "nothing is prepared before the sweep runs");

        sweep.sweep();

        var prepared = store.load(id).preparation();
        assertNotNull(prepared, "the ticket alone must be enough");
        assertEquals(WorkPreparation.STORED_BINDING, prepared.bindingVersion());
        assertEquals(WorkPreparation.Origin.STORED, prepared.specification().origin());
        assertEquals(WorkPreparation.Origin.STORED, prepared.plan().origin());
        // The build coordinates come from the repository's saved setup, and the commit from the forge.
        assertEquals("main", prepared.baseBranch());
        assertEquals("d".repeat(40), prepared.baseCommit());
        assertEquals("codex", prepared.harness());
        assertEquals(model, prepared.model());
        assertTrue(prepared.registeredBy().startsWith("system:"), prepared.registeredBy());
        assertEquals(0, attempts(id), "a prepared generation has nothing left to report");

        // The stored specification is the operator's own ticket, and the plan names it by digest.
        String specification = stored.read(id, prepared.specification().storedId()).orElseThrow();
        assertTrue(specification.contains("TEST-identical task"), specification);
        String plan = stored.read(id, prepared.plan().storedId()).orElseThrow();
        assertTrue(plan.contains(prepared.specification().sha256()), plan);
    }

    /** The evidence an approver is shown comes from the stored bytes, and matches what the gate binds. */
    @Test
    void theApproverSeesTheComposedTextsAndTheGateBindsThem() throws Exception {
        String id = admit("assisted", 81);
        sweep.sweep();

        var item = store.load(id);
        var observed = transitions.observe(item);
        assertNull(observed.artifacts().failure(), observed.artifacts().detail());
        assertTrue(observed.artifacts().specification().contains("TEST-identical task"));
        assertEquals("Implement the specification in full, and change nothing it does not ask for.",
                observed.artifacts().instruction());
        assertEquals(item.preparation().binding(), dev.codespire.contract.work.WorkGate.artifactOf(item));
    }

    /**
     * The point of storing the texts. A ticket edited after preparation does not change what was
     * approved — with tracker artifacts this same edit refused the build as {@code artifacts_changed}.
     */
    @Test
    void editingTheTicketAfterwardsDoesNotChangeWhatWasApproved() throws Exception {
        String id = admit("assisted", 82);
        sweep.sweep();
        String binding = store.load(id).preparation().binding();

        var edited = mapper.createObjectNode().put("id", 50082).put("number", 82)
                .put("repository_url", forge.baseUrl() + "/repos/" + scope)
                .put("html_url", forge.baseUrl() + "/" + scope + "/issues/82")
                .put("title", "TEST-prepared-task").put("body", "TEST-a completely different task").put("state", "open");
        edited.putArray("labels").add("TEST-assisted");
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/issues/82")).willReturn(okJson(edited.toString())));

        var observed = transitions.observe(store.load(id));
        assertNull(observed.artifacts().failure(), "the stored bytes are what the decision binds");
        assertTrue(observed.artifacts().specification().contains("TEST-identical task"));
        assertEquals(binding, store.load(id).preparation().binding());
    }

    @Test
    void aRepositoryWithoutBuildDefaultsIsNotPreparedAndSaysWhy() throws Exception {
        execute("DELETE FROM repository_build_defaults WHERE repository_id=?", repository);
        String id = admit("assisted", 83);
        sweep.sweep();
        assertNull(store.load(id).preparation());
        assertEquals("build_defaults_missing", reason(id));
    }

    /** An empty ticket cannot be a specification, and saying so is the whole value of the record. */
    @Test
    void anEmptyTicketIsRefusedWithAReasonThatSurvivesTheSweep() throws Exception {
        String id = admit("assisted", 84);
        var empty = mapper.createObjectNode().put("id", 50084).put("number", 84)
                .put("repository_url", forge.baseUrl() + "/repos/" + scope)
                .put("html_url", forge.baseUrl() + "/" + scope + "/issues/84")
                .put("title", "TEST-prepared-task").put("body", "   ").put("state", "open");
        empty.putArray("labels").add("TEST-assisted");
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/issues/84")).willReturn(okJson(empty.toString())));

        sweep.sweep();
        assertNull(store.load(id).preparation());
        assertEquals("ticket_body_empty", reason(id));
        // And the workflow reason is untouched, so the item still matches the sweep's own trigger.
        assertEquals("specification_required", store.load(id).reason());
    }

    /** A refusal must not be retried every twenty seconds for ever; the row holds the item back. */
    @Test
    void aRefusedItemBacksOffInsteadOfAskingTheForgeAgainImmediately() throws Exception {
        execute("DELETE FROM repository_build_defaults WHERE repository_id=?", repository);
        String id = admit("assisted", 85);
        sweep.sweep();
        assertEquals(1, attempts(id));
        sweep.sweep();
        assertEquals(1, tries(id), "a second sweep must not even look at an item that is backing off");

        // ...and when the wait is over it IS tried again, which is the half a "never retry" bug passes.
        execute("UPDATE work_item_preparation_attempt SET retry_after=now()-interval '1 minute' WHERE work_item_id=?", id);
        sweep.sweep();
        assertEquals(2, tries(id), "an item whose backoff expired must be retried");
    }

    private int tries(String id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT attempts FROM work_item_preparation_attempt WHERE work_item_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
        }
    }

    /** A forge that cannot answer for the branch head is a fault, not a reason to build from nothing. */
    @Test
    void anUnreadableBranchHeadStopsThePreparationRatherThanGuessingACommit() throws Exception {
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/branches/main")).willReturn(WireMock.aResponse().withStatus(500)));
        String id = admit("assisted", 86);
        sweep.sweep();
        assertNull(store.load(id).preparation());
        assertEquals("branch_head_unconfirmed", reason(id));
    }

    /**
     * Preparing again after a deliberate ticket edit: NEW stored rows, a new binding, and the old bytes
     * still readable for whatever already bound them.
     */
    @Test
    void preparingAgainComposesTheEditedTicketWithoutRewritingTheOldBytes() throws Exception {
        String id = admit("assisted", 88);
        sweep.sweep();
        var first = store.load(id).preparation();

        var edited = mapper.createObjectNode().put("id", 50088).put("number", 88)
                .put("repository_url", forge.baseUrl() + "/repos/" + scope)
                .put("html_url", forge.baseUrl() + "/" + scope + "/issues/88")
                .put("title", "TEST-prepared-task").put("body", "TEST-the operator rewrote this on purpose").put("state", "open");
        edited.putArray("labels").add("TEST-assisted");
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/issues/88")).willReturn(okJson(edited.toString())));

        assertTrue(sweep.prepareAgain(id).prepared());
        var second = store.load(id).preparation();
        assertNotEquals(first.specification().storedId(), second.specification().storedId());
        assertNotEquals(first.binding(), second.binding());
        assertTrue(stored.read(id, second.specification().storedId()).orElseThrow().contains("rewrote this on purpose"));
        // The bytes the first preparation bound are still there: a gate or a held run may still name them.
        assertTrue(stored.read(id, first.specification().storedId()).orElseThrow().contains("TEST-identical task"));
    }

    /**
     * An item a person prepared by hand is left alone. The sweep's own query would not reach it — a
     * prepared item is no longer waiting for a specification — so the guard is asked DIRECTLY here,
     * which is the only way this refusal can be shown to exist.
     */
    @Test
    void anItemPreparedByHandIsNotPreparedAgain() throws Exception {
        String id = admit("assisted", 87);
        register(id);
        var byHand = store.load(id).preparation();
        sweep.sweep();
        assertEquals(byHand, store.load(id).preparation());
        var refused = sweep.prepare(id);
        assertFalse(refused.prepared());
        assertEquals("already_prepared", refused.reason());
        assertEquals(byHand, store.load(id).preparation());
        assertEquals(WorkPreparation.Origin.TRACKER, store.load(id).preparation().specification().origin());
    }
}
