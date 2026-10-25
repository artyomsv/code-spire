package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.WorkItemIds;
import dev.codespire.contract.work.WorkGate;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.contract.work.WorkPolicyLimits;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.worksource.WorkIssueLocation;
import dev.codespire.worksource.WorkIssueRef;
import dev.codespire.worksource.WorkSourceType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A generated repository admits its new items as drafts, and the draft sweep starts their specification (M4 slice 3,
 * A4, A5, A16). Nothing dispatches the run yet: its effect row stays pending.
 */
@QuarkusTest
@TestProfile(GeneratedPreparationProfile.class)
@TestSecurity(user = "TEST-draft-admin", roles = "spire-admin")
class WorkDraftAdmissionIT extends WorkPreparedFixture {

    @Inject BuildDefaults defaults;
    @Inject WorkDraftSweep draftSweep;
    @Inject WorkPreparationSweep copySweep;
    @Inject WorkDraftProjection draftProjection;
    @Inject WorkItemControl control;

    @BeforeEach
    void buildSetup() throws Exception {
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/branches/main"))
                .willReturn(okJson("{\"name\":\"main\",\"commit\":{\"sha\":\"" + "d".repeat(40) + "\"}}")));
        preparingWith(BuildDefaults.COMPOSED);
        specApproveProfile();
    }

    private void preparingWith(String preparation) {
        defaults.save(repository, new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null,
                List.of(), null, preparation), "TEST-draft-admin");
    }

    /** Intake auto and spec approve, under the label TEST-spec-approve: what Review Focus 1 parks. */
    private void specApproveProfile() {
        EnumMap<WorkPolicy.Phase, String> modes = new EnumMap<>(WorkPolicy.Phase.class);
        for (WorkPolicy.Phase phase : WorkPolicy.Phase.values()) modes.put(phase, switch (phase) {
            case DELIVER -> "pr"; case LAND -> "auto_if_green"; case SPEC -> "approve"; default -> "auto";
        });
        UUID id = UUID.randomUUID();
        extraProfiles.add(id);
        WorkPolicy.Profile created = policies.createVersion(new WorkPolicy.Profile(id, "TEST-spec-approve-" + id, 1,
                1_500_000_000 + Math.abs(id.hashCode() % 100_000_000),
                modes, new WorkPolicyLimits(3600, 5, 20, 7200, 2_000_000, 40, Set.of("TEST-sensitive/**"))));
        Map<String, WorkPolicyRegistry.Pin> mapping = new HashMap<>();
        profiles.forEach((name, profile) -> mapping.put("TEST-" + name, new WorkPolicyRegistry.Pin(profile.id(), 1)));
        mapping.put("TEST-spec-approve", new WorkPolicyRegistry.Pin(created.id(), 1));
        policies.save(repository, new WorkPolicyRegistry.Input(policies.get(repository).revision(),
                new WorkPolicyRegistry.Pin(profiles.get("autonomous").id(), 1), mapping));
    }

    /** One observation of a ticket, labelled or not, as the scanner or a webhook would make it. */
    private String observe(int number, String label) throws Exception {
        WorkIssueLocation location = ticket(number, label);
        WorkSourceRegistry.Source sourceRow = sources.get(source).orElseThrow();
        String id = WorkItemIds.of(sourceRow.scm(), sourceRow.forgeOrigin(), sourceRow.repository(), location.ref());
        extraItems.add(id);
        WorkEvidence evidence = WorkEvidence.collect(() -> sources.client(sourceRow), location, null);
        assertNull(evidence.failure());
        store.reconcile(sourceRow, policies.get(repository).revision(), evidence, "TEST-draft-observation-" + UUID.randomUUID());
        return id;
    }

    /** The ticket as the forge now answers it, labelled or not, without observing it. */
    private WorkIssueLocation ticket(int number, String label) {
        WorkIssueRef ref = new WorkIssueRef(WorkSourceType.GITHUB, forge.baseUrl(), "10001", Long.toString(50000 + number));
        WorkIssueLocation location = new WorkIssueLocation(ref, Integer.toString(number), URI.create(forge.baseUrl() + "/" + scope + "/issues/" + number));
        var ticket = mapper.createObjectNode().put("id", 50000 + number).put("number", number).put("repository_url", forge.baseUrl() + "/repos/" + scope)
                .put("html_url", location.link().toString()).put("title", "TEST-ticket: make the TEST-widget faster").put("body", "TEST-body")
                .put("state", "open");
        var labels = ticket.putArray("labels");
        var timeline = mapper.createArrayNode();
        if (label != null) {
            labels.add(label);
            var event = timeline.addObject().put("id", 100 + number).put("event", "labeled").put("created_at", "2026-10-25T00:00:00Z");
            event.putObject("label").put("name", label);
            event.putObject("actor").put("id", 900123);
        }
        String endpoint = "/repos/" + scope + "/issues/" + number;
        forge.stubFor(get(urlEqualTo(endpoint)).willReturn(okJson(ticket.toString())));
        forge.stubFor(get(urlEqualTo(endpoint + "/timeline?per_page=100&page=1")).willReturn(okJson(timeline.toString())));
        return location;
    }

    private String mode(String id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT preparation_mode FROM work_item WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private long effects(String id) throws SQLException {
        return count("SELECT count(*) FROM work_prepare_effect WHERE work_item_id=?", id);
    }

    private static void assertState(WorkItemEvent item, String phase, String status, String reason) {
        assertEquals(phase + " / " + status + " / " + reason, item.phase() + " / " + item.workflowStatus() + " / " + item.reason());
    }

    @Test
    void aGeneratedRepositoryAdmitsADraft() throws Exception {
        preparingWith(BuildDefaults.GENERATED);

        String id = admit("autonomous", 61);

        WorkItemEvent item = store.load(id);
        assertState(item, "spec", "awaiting_input", "specification_to_write");
        assertNotNull(item.draft());
        assertNull(item.preparation());
        assertEquals("generated", mode(id));
    }

    @Test
    void aScannedTicketLabelledLaterIsDrafted() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = observe(62, null);
        assertEquals("not_eligible", store.load(id).workflowStatus());
        assertNull(store.load(id).admittedProfile());

        observe(62, "TEST-autonomous");

        assertState(store.load(id), "spec", "awaiting_input", "specification_to_write");
        assertNotNull(store.load(id).draft());
        assertEquals("generated", mode(id));
    }

    @Test
    void theDraftSweepStartsOneSpecAttempt() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = admit("autonomous", 63);

        draftSweep.sweep();

        WorkItemEvent item = store.load(id);
        assertState(item, "spec", "active", "phase_started");
        assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE work_item_id=? AND attempt_id=? AND kind='SPEC' AND state='pending'",
                id, item.progress().attemptId()));
        draftSweep.sweep();
        assertEquals(1, effects(id));
        assertEquals("pending", stateOf(item.progress().attemptId()));
    }

    @Test
    void aDraftPhaseStartInsertsOnePrepareEffect() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = admit("autonomous", 64);
        draftSweep.sweep();
        WorkItemEvent started = store.load(id);

        try (Connection c = dataSource.getConnection()) { draftProjection.project(c, started); }

        assertEquals(1, effects(id));
    }

    @Test
    void theDraftSweepRecordsBuildDefaultsMissing() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = admit("autonomous", 65);
        executeWith("DELETE FROM repository_build_defaults WHERE repository_id=?", repository);

        draftSweep.sweep();

        assertState(store.load(id), "spec", "awaiting_input", "specification_to_write");
        assertEquals("build_defaults_missing", copySweep.health(id, 1).map(WorkPreparationSweep.Health::reason).orElse(null));
        assertEquals(0, effects(id));
    }

    @Test
    void aTakeoverRefusesAPendingPrepareEffect() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = admit("autonomous", 66);
        draftSweep.sweep();
        UUID attempt = store.load(id).progress().attemptId();
        assertNotNull(attempt);

        control.suspend(id, "tracker", "TEST-takeover-" + UUID.randomUUID(), "900123", null);

        assertEquals("suspended", store.load(id).workflowStatus());
        assertEquals("refused", stateOf(attempt));
        assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE attempt_id=? AND reason='work_item_invalidated'", attempt));
    }

    @Test
    void aSpecGateItemAdmittedBeforeTheSwitchIsNotDrafted() throws Exception {
        String id = admit("spec-approve", 67);
        WorkGate gate = store.load(id).gate();
        assertNotNull(gate);
        assertEquals("waiting_approval", store.load(id).workflowStatus());
        preparingWith(BuildDefaults.GENERATED);

        var outcome = transitions.answer(gate.id(), gate.version(), "TEST-spec-approval", true, null, "TEST-draft-admin");

        assertEquals(200, outcome.status(), outcome.reason());
        assertState(store.load(id), "spec", "capability_unavailable", "spec_capability_unavailable");
        assertNull(store.load(id).draft());
        assertEquals(0, effects(id));
        assertEquals("composed", mode(id));
    }

    /** A5 resolved against A16: the copy sweep follows how the item was admitted, not the repository's setting now. */
    @Test
    void theCopySweepSkipsAnItemAdmittedAsGenerated() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = admit("autonomous", 68);
        preparingWith(BuildDefaults.COMPOSED);

        copySweep.sweep();

        assertNull(store.load(id).preparation());
        assertState(store.load(id), "spec", "awaiting_input", "specification_to_write");
    }

    /**
     * A drafted item standing at the copy sweep's own reason, so its reason filter excludes nothing and each draft guard
     * is the only one that can: the mode filter in the query, and the draft in the trigger.
     */
    private String draftAtTheCopyReason(int number) throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = admit("autonomous", number);
        WorkItemEvent drafted = store.load(id);
        WorkItemEvent misplaced = WorkItemLifecycle.state(drafted, "awaiting_input", "specification_required", "TEST-DRAFT-AT-COPY-REASON",
                null, drafted.progress());
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkItemTransitions.lockItem(c, id);
                store.appendDecision(c, store.history(id), misplaced, "TEST-draft-at-copy-reason:" + UUID.randomUUID());
            } catch (SQLException | java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        assertEquals("generated", mode(id));
        assertState(store.load(id), "spec", "awaiting_input", "specification_required");
        return id;
    }

    @Test
    void theCopySweepPreparesNoDraftAtItsOwnReason() throws Exception {
        String id = draftAtTheCopyReason(70);

        copySweep.sweep();

        assertNull(store.load(id).preparation());
        assertNotNull(store.load(id).draft());
    }

    @Test
    void theCopySweepSelectsNoGeneratedItem() throws Exception {
        String id = draftAtTheCopyReason(71);

        assertFalse(copySweep.candidates().contains(id));
    }

    @Test
    void theCopyTriggerRejectsADraft() throws Exception {
        String id = draftAtTheCopyReason(72);

        assertFalse(WorkPreparationSweep.automatic(store.load(id)));
    }

    /** The source, its repository or its account switched off after admission: nothing is bought for it. */
    @Test
    void theDraftSweepStartsNothingForADisabledSource() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = admit("autonomous", 73);
        executeWith("UPDATE work_source SET enabled=false,revision=revision+1 WHERE id=?", source);

        draftSweep.sweep();

        assertState(store.load(id), "spec", "awaiting_input", "specification_to_write");
        assertNull(store.load(id).progress().attemptId());
        assertEquals(0, effects(id));
        assertEquals("source_unavailable", copySweep.health(id, 1).map(WorkPreparationSweep.Health::reason).orElse(null));
    }

    /** The label was removed after the last observation: the start selects from the tracker as it reads now. */
    @Test
    void theDraftSweepSelectsThePolicyFromTheTicketAsItReadsNow() throws Exception {
        preparingWith(BuildDefaults.GENERATED);
        String id = observe(74, "TEST-autonomous");
        assertState(store.load(id), "spec", "awaiting_input", "specification_to_write");
        ticket(74, null);

        draftSweep.sweep();

        assertEquals("not_eligible", store.load(id).workflowStatus());
        assertNull(store.load(id).progress().attemptId());
        assertEquals(0, effects(id));
    }

    @Test
    void aComposedItemIsStillCopiedAfterASwitchToGenerated() throws Exception {
        String id = admit("autonomous", 69);
        assertEquals("specification_required", store.load(id).reason());
        preparingWith(BuildDefaults.GENERATED);

        copySweep.sweep();

        assertNotNull(store.load(id).preparation());
        assertNull(store.load(id).draft());
        assertEquals("composed", mode(id));
    }

    private String stateOf(UUID attempt) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT state FROM work_prepare_effect WHERE attempt_id=?")) {
            ps.setObject(1, attempt);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
}
