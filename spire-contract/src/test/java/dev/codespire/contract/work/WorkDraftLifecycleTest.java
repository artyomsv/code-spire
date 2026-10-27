package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admission and entry of a generated item (M4 slice 3, A16, decision 10): only an item not yet admitted is drafted, and
 * only the draft sweep's entry starts a draft run.
 */
class WorkDraftLifecycleTest {
    final UUID source = UUID.randomUUID(), repository = UUID.randomUUID();
    final WorkIssueLocation issue = WorkDraftTest.ISSUE;
    final WorkItemEvent.Authority authority = new WorkItemEvent.Authority(UUID.randomUUID(), 1, 1, 1);
    static final WorkPolicyLimits LIMITS = new WorkPolicyLimits(60, 5, 20, 7200, 2_000_000, 40, Set.of());

    static WorkPolicy.Profile profile(String intake, String spec, String plan) {
        Map<WorkPolicy.Phase, String> modes = new EnumMap<>(WorkPolicy.Phase.class);
        modes.put(WorkPolicy.Phase.INTAKE, intake);
        modes.put(WorkPolicy.Phase.SPEC, spec);
        modes.put(WorkPolicy.Phase.PLAN, plan);
        return new WorkPolicy.Profile(UUID.randomUUID(), "TEST-profile", 1, 1, modes, LIMITS);
    }

    static WorkPolicy.Selection selected(WorkPolicy.Profile profile) {
        return new WorkPolicy.Selection(profile, profile.modes(), List.of(), "policy_selected", List.of(), profile, profile.limits());
    }

    static WorkPolicy.Selection unselected(WorkPolicy.Profile ceiling) {
        return new WorkPolicy.Selection(null, ceiling.modes(), List.of(), "no_eligible_label", List.of(), ceiling, ceiling.limits());
    }

    WorkItemEvent observe(long revision, WorkPolicy.Selection selection, WorkItemEvent previous) {
        return WorkItemLifecycle.reconcile("TEST-item", source, repository, issue, revision, authority, selection, previous);
    }

    WorkItemEvent admit(WorkPolicy.Profile profile) {
        return WorkDraftLifecycle.admitGenerated(null, observe(1, selected(profile), null));
    }

    static WorkItemEvent enter(WorkItemEvent item, boolean approved, WorkItemLifecycle.EntryMode mode) {
        return WorkItemLifecycle.enter(item, item, 4, approved, Instant.EPOCH, true, UUID.randomUUID(), mode);
    }

    static WorkItemEvent at(WorkItemEvent item, String phase, String status, String reason) {
        return item.decision(item.policyRevision(), item.authority(), item.policy(), phase, status, reason, "TEST", item.gate(), item.progress());
    }

    @Test
    void aFreshSpecAdmissionBecomesSpecificationToWrite() {
        WorkItemEvent item = admit(profile("auto", "approve", "approve"));

        assertEquals("spec", item.phase());
        assertEquals("awaiting_input", item.workflowStatus());
        assertEquals("specification_to_write", item.reason());
        assertEquals("DRAFT_ADMITTED", item.milestone());
        assertEquals(WorkDraft.admitted(), item.draft());
    }

    @Test
    void aFreshAutomaticSpecAdmissionIsDraftedToo() {
        WorkItemEvent item = admit(profile("auto", "auto", "auto"));

        assertEquals("specification_to_write", item.reason());
        assertNotNull(item.draft());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gate_open", "gate_approved"})
    void itemsParkedBeforeTheSwitchStayParked(String parked) {
        WorkPolicy.Profile profile = profile("auto", "approve", "approve");
        WorkItemEvent admitted = observe(1, selected(profile), null);
        WorkGate gate = new WorkGate(UUID.randomUUID(), 1, "OPEN", "spec", 1, 2, 1, authority, null, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), null, null, null, null);
        WorkItemEvent previous = "gate_open".equals(parked)
                ? WorkItemLifecycle.state(admitted, "waiting_approval", "approval_required", "GATE_OPENED", gate, admitted.progress().reserve(true))
                : WorkItemLifecycle.state(admitted, "capability_unavailable", "spec_capability_unavailable", "CAPABILITY_UNAVAILABLE",
                        gate.resolve("APPROVED", "TEST-operator", "TEST-key", null), admitted.progress());
        WorkItemEvent reconciled = observe(2, selected(profile), previous);

        WorkItemEvent next = WorkDraftLifecycle.admitGenerated(previous, reconciled);

        assertEquals(reconciled, next);
        assertNull(next.draft());
        assertEquals(previous.reason(), next.reason());
    }

    @Test
    void aTicketLabelledAfterItWasOpenedIsDrafted() {
        WorkPolicy.Profile profile = profile("auto", "auto", "auto");
        WorkItemEvent unlabelled = observe(1, unselected(profile), null);
        assertNull(unlabelled.admittedProfile());

        WorkItemEvent next = WorkDraftLifecycle.admitGenerated(unlabelled, observe(1, selected(profile), unlabelled));

        assertEquals("specification_to_write", next.reason());
        assertNotNull(next.draft());
    }

    @Test
    void anUnselectedItemIsNotDrafted() {
        WorkItemEvent unselected = observe(1, unselected(profile("auto", "auto", "auto")), null);

        WorkItemEvent next = WorkDraftLifecycle.admitGenerated(null, unselected);

        assertNull(next.draft());
        assertEquals(unselected.reason(), next.reason());
        assertEquals(unselected, next);
    }

    @Test
    void aDeselectedItemIsNotDrafted() {
        WorkPolicy.Profile profile = profile("auto", "auto", "auto");
        WorkItemEvent selected = observe(1, selected(profile), null);
        WorkItemEvent deselected = observe(2, unselected(profile), selected);
        assertNotNull(deselected.admittedProfile());
        WorkItemEvent relabelled = observe(3, selected(profile), deselected);

        WorkItemEvent next = WorkDraftLifecycle.admitGenerated(deselected, relabelled);

        assertEquals(relabelled, next);
        assertNull(next.draft());
    }

    @Test
    void anIntakeApprovedItemIsDraftedToo() {
        WorkItemEvent item = admit(profile("approve", "approve", "approve"));
        assertEquals("intake", item.phase());
        assertEquals("intake_approval_unavailable", item.reason());
        assertNotNull(item.draft());

        WorkItemEvent next = enter(item, true, WorkItemLifecycle.EntryMode.ORDINARY);

        assertEquals("spec", next.phase());
        assertEquals("awaiting_input", next.workflowStatus());
        assertEquals("specification_to_write", next.reason());
        assertNull(next.progress().attemptId());
    }

    static String state(WorkItemEvent item) {
        return item.phase() + " / " + item.workflowStatus() + " / " + item.reason();
    }

    /** Nothing written, no attempt, no gate: a removed label stops the draft as it stops a composed item. */
    @Test
    void aRemovedLabelStopsADraftWithNothingWritten() {
        WorkPolicy.Profile profile = profile("auto", "approve", "approve");
        WorkItemEvent drafted = admit(profile);

        WorkItemEvent next = observe(2, unselected(profile), drafted);

        assertEquals("intake / not_eligible / no_eligible_label", state(next));
        assertEquals(drafted.draft(), next.draft());
    }

    /** The sweep refused the unlabelled draft; the label coming back drafts it again, with no Readmit. */
    @Test
    void aRelabelAfterTheSweepsRefusalDraftsAgain() {
        WorkPolicy.Profile profile = profile("auto", "approve", "approve");
        WorkItemEvent drafted = admit(profile);
        WorkItemEvent refused = enter(observe(2, unselected(profile), drafted), false, WorkItemLifecycle.EntryMode.DRAFT);
        assertEquals("not_eligible", refused.workflowStatus());

        WorkItemEvent next = observe(3, selected(profile), refused);

        assertEquals("spec / awaiting_input / specification_to_write", state(next));
        assertEquals(drafted.draft(), next.draft());
        assertEquals(drafted.admittedProfile(), next.admittedProfile());
    }

    @Test
    void specOffThenOnDraftsAgain() {
        WorkPolicy.Profile profile = profile("auto", "approve", "approve");
        WorkItemEvent drafted = admit(profile);
        WorkItemEvent refused = enter(observe(2, selected(profile("auto", "off", "approve")), drafted), false,
                WorkItemLifecycle.EntryMode.DRAFT);
        assertEquals("not_eligible / spec_off", refused.workflowStatus() + " / " + refused.reason());

        WorkItemEvent next = observe(3, selected(profile), refused);

        assertEquals("spec / awaiting_input / specification_to_write", state(next));
        assertEquals(drafted.draft(), next.draft());
    }

    /** Drafted at intake_off (admission drafts whatever the phase), then re-evaluated once the profile is fixed. */
    @Test
    void aDraftAdmittedWithIntakeOffIsReevaluated() {
        WorkItemEvent drafted = admit(profile("off", "auto", "auto"));
        assertEquals("intake / not_eligible / intake_off", state(drafted));
        assertNotNull(drafted.draft());

        WorkItemEvent next = observe(2, selected(profile("auto", "auto", "auto")), drafted);

        assertEquals("spec / awaiting_input / specification_to_write", state(next));
        assertEquals(drafted.draft(), next.draft());
    }

    /** A written specification is worth keeping: the item stays where it stands, as a composed preparation does. */
    @Test
    void aWrittenSpecificationKeepsTheItem() {
        WorkPolicy.Profile profile = profile("auto", "approve", "approve");
        WorkItemEvent drafted = admit(profile);
        WorkItemEvent written = at(drafted, "spec", "waiting_approval", "approval_required")
                .drafted(drafted.draft().withSpecification(new WorkDraft.Written(WorkDraftTest.stored('a'), "c".repeat(40), 1)));

        WorkItemEvent next = observe(2, unselected(profile), written);

        assertEquals("spec / waiting_approval / approval_required", state(next));
        assertEquals(written.draft(), next.draft());
    }

    @Test
    void aPolicyChangeKeepsTheDraft() {
        WorkPolicy.Profile profile = profile("auto", "approve", "approve");
        WorkItemEvent drafted = admit(profile);

        WorkItemEvent next = observe(2, selected(profile), drafted);

        assertEquals(drafted.draft(), next.draft());
        assertEquals("specification_to_write", next.reason());
        assertEquals(2, next.policyRevision());
    }

    @Test
    void aPreparedItemIsNotDrafted() {
        WorkPolicy.Profile profile = profile("auto", "auto", "auto");
        WorkItemEvent prepared = observe(1, selected(profile), null).prepared(new WorkPreparation(WorkDraftTest.stored('a'),
                WorkDraftTest.stored('b'), "main", "c".repeat(40), "TEST-harness", "TEST-model", "TEST-operator",
                WorkPreparation.VERIFY_BINDING, null, null, List.of(), 60));
        WorkItemEvent reconciled = observe(2, selected(profile), prepared);

        WorkItemEvent next = WorkDraftLifecycle.admitGenerated(prepared, reconciled);

        assertEquals(reconciled, next);
        assertNull(next.draft());
    }

    @Test
    void anOrdinaryEntryParks() {
        WorkItemEvent drafted = admit(profile("auto", "approve", "approve"));

        WorkItemEvent spec = enter(drafted, false, WorkItemLifecycle.EntryMode.ORDINARY);
        assertEquals("awaiting_input", spec.workflowStatus());
        assertEquals("specification_to_write", spec.reason());
        assertNull(spec.gate());
        assertNull(spec.progress().attemptId());

        WorkItemEvent plan = enter(at(drafted, "plan", "awaiting_input", "TEST"), false, WorkItemLifecycle.EntryMode.ORDINARY);
        assertEquals("awaiting_input", plan.workflowStatus());
        assertEquals("plan_to_write", plan.reason());
        assertNull(plan.gate());
        assertNull(plan.progress().attemptId());
    }

    @Test
    void aDraftEntrySkipsThePermissionGate() {
        WorkItemEvent drafted = admit(profile("auto", "approve", "approve"));

        WorkItemEvent next = enter(drafted, false, WorkItemLifecycle.EntryMode.DRAFT);

        assertEquals("active", next.workflowStatus());
        assertEquals("PHASE_STARTED", next.milestone());
        assertNull(next.gate());
        assertEquals("spec", next.progress().attemptPhase());
        assertNotNull(next.progress().attemptId());
    }

    @Test
    void aDraftEntryWithoutARunTransportIsUnavailable() {
        WorkItemEvent drafted = admit(profile("auto", "approve", "approve"));

        WorkItemEvent next = WorkItemLifecycle.enter(drafted, drafted, 4, false, Instant.EPOCH, false, UUID.randomUUID(), WorkItemLifecycle.EntryMode.DRAFT);

        assertEquals("capability_unavailable", next.workflowStatus());
        assertNull(next.progress().attemptId());
    }

    @Test
    void aDraftEntryStillHonoursTheCaps() {
        WorkItemEvent drafted = admit(profile("auto", "approve", "approve"));
        WorkItemEvent spent = WorkItemLifecycle.state(drafted, drafted.workflowStatus(), drafted.reason(), drafted.milestone(), null,
                drafted.progress().account(7200, 0, 0));

        WorkItemEvent next = enter(spent, false, WorkItemLifecycle.EntryMode.DRAFT);

        assertEquals("stopped", next.workflowStatus());
        assertEquals("policy_cap_reached", next.reason());
    }

    static final WorkDraft.Written WRITTEN = new WorkDraft.Written(WorkDraftTest.stored('a'), "c".repeat(40), 1);
    static final WorkDraftLifecycle.RunUsage USAGE = new WorkDraftLifecycle.RunUsage(5, 0, 1, true);
    static final WorkItemLifecycle.GateOpening OPENING = new WorkItemLifecycle.GateOpening(6, Instant.EPOCH, UUID.randomUUID());

    WorkItemEvent started(WorkPolicy.Profile profile) {
        WorkItemEvent started = enter(admit(profile), false, WorkItemLifecycle.EntryMode.DRAFT);
        assertEquals("spec / active / phase_started", state(started));
        return started;
    }

    @Test
    void specApproveOpensAResultGateOnTheArtifact() {
        WorkItemEvent written = WorkDraftLifecycle.written(started(profile("auto", "approve", "approve")), WRITTEN, USAGE);
        assertEquals("DRAFT_ARTIFACT_WRITTEN", written.milestone());
        assertEquals("completed", written.progress().attemptState());
        assertEquals(WRITTEN.artifact(), written.draft().specification());

        WorkItemEvent next = WorkDraftLifecycle.afterWritten(written, OPENING);

        assertEquals("spec / waiting_approval / specification_written", state(next));
        assertEquals("GATE_OPENED", next.milestone());
        assertEquals("spec", next.gate().phase());
        assertEquals("a".repeat(64), next.gate().artifact());
        assertEquals(OPENING.historySize() + 1, next.gate().itemRevision());
        assertTrue(WorkDraftLifecycle.resultGate(next, next.gate()));
    }

    @Test
    void specAutoMovesToPlan() {
        WorkItemEvent written = WorkDraftLifecycle.written(started(profile("auto", "auto", "auto")), WRITTEN, USAGE);

        WorkItemEvent next = WorkDraftLifecycle.afterWritten(written, OPENING);

        assertEquals("DRAFT_APPROVED", next.milestone());
        assertEquals("plan", next.phase());
        assertTrue(next.draft().specificationApproved());
        assertNull(next.gate());
        WorkItemEvent parked = enter(next, false, WorkItemLifecycle.EntryMode.ORDINARY);
        assertEquals("plan / awaiting_input / plan_to_write", state(parked));
        assertNull(parked.gate());
    }

    @Test
    void approvingAResultGateMovesToPlan() {
        WorkItemEvent gated = WorkDraftLifecycle.afterWritten(
                WorkDraftLifecycle.written(started(profile("auto", "approve", "auto")), WRITTEN, USAGE), OPENING);

        WorkItemEvent next = WorkDraftLifecycle.approved(gated);

        assertEquals("plan", next.phase());
        assertEquals("DRAFT_APPROVED", next.milestone());
        assertTrue(next.draft().specificationApproved());
        assertFalse(next.progress().reserved());
    }

    @Test
    void aPermissionGateIsNotAResultGate() {
        WorkGate gate = new WorkGate(UUID.randomUUID(), 1, "OPEN", "spec", 1, 2, 1, authority, null, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), null, null, null, null);
        WorkItemEvent drafted = admit(profile("auto", "approve", "approve"));
        WorkItemEvent waiting = WorkItemLifecycle.state(drafted, "waiting_approval", "approval_required", "GATE_OPENED", gate, drafted.progress());

        assertFalse(WorkDraftLifecycle.resultGate(waiting, gate));
    }

    @Test
    void anInvalidSpecificationRetriesInANewAttempt() {
        WorkItemEvent started = started(profile("auto", "approve", "approve"));
        UUID retry = UUID.randomUUID();

        WorkItemEvent next = WorkDraftLifecycle.retryRequested(started, USAGE, new WorkDraftLifecycle.Restart(retry, Instant.EPOCH));

        assertEquals("spec / active / phase_started", state(next));
        assertEquals("DRAFT_RETRY_REQUESTED", next.milestone());
        assertEquals(retry, next.progress().attemptId());
        assertEquals("started", next.progress().attemptState());
        assertEquals(5, next.progress().wallSeconds());
        assertEquals(1, next.progress().calls());
        assertEquals(0, next.progress().runs());
    }

    /** The refused attempt is recorded completed before the retry starts another: one started attempt per phase (V87). */
    @Test
    void aRefusedArtifactFinishesItsAttempt() {
        WorkItemEvent started = started(profile("auto", "approve", "approve"));

        WorkItemEvent refused = WorkDraftLifecycle.artifactRefused(started, USAGE);

        assertEquals("DRAFT_ARTIFACT_REFUSED", refused.milestone());
        assertEquals(started.progress().attemptId(), refused.progress().attemptId());
        assertEquals("completed", refused.progress().attemptState());
        assertEquals(5, refused.progress().wallSeconds());
    }

    @Test
    void aRetryPastTheCapsStops() {
        WorkItemEvent started = started(profile("auto", "approve", "approve"));
        WorkItemEvent spent = WorkItemLifecycle.state(started, started.workflowStatus(), started.reason(), started.milestone(), null,
                started.progress().account(7195, 0, 0));

        WorkItemEvent next = WorkDraftLifecycle.retryRequested(spent, USAGE, new WorkDraftLifecycle.Restart(UUID.randomUUID(), Instant.EPOCH));

        assertEquals("spec / stopped / policy_cap_reached", state(next));
    }

    @Test
    void aSecondInvalidSpecificationWaits() {
        WorkItemEvent next = WorkDraftLifecycle.invalid(started(profile("auto", "approve", "approve")), USAGE);

        assertEquals("spec / awaiting_input / spec_invalid", state(next));
        assertEquals("completed", next.progress().attemptState());
        assertNull(next.draft().specification());
    }

    @Test
    void aFailedSpecRunWaitsNotFails() {
        WorkItemEvent next = WorkDraftLifecycle.runFailed(started(profile("auto", "approve", "approve")), USAGE);

        assertEquals("spec / awaiting_input / draft_run_failed", state(next));
        assertEquals("completed", next.progress().attemptState());
    }

    @Test
    void anUnknownUsageIsRecordedAsUnknown() {
        WorkItemEvent next = WorkDraftLifecycle.runFailed(started(profile("auto", "approve", "approve")),
                new WorkDraftLifecycle.RunUsage(5, 0, 1, false));

        assertTrue(next.progress().usageUnknown());
    }

    @Test
    void specOffStaysOff() {
        WorkItemEvent drafted = admit(profile("auto", "approve", "approve"));
        WorkPolicy.Profile off = profile("auto", "off", "approve");
        WorkItemEvent switched = drafted.decision(2, drafted.authority(), selected(off), "spec", "awaiting_input", "specification_to_write",
                "TEST", null, drafted.progress());

        WorkItemEvent next = enter(switched, false, WorkItemLifecycle.EntryMode.DRAFT);

        assertEquals("not_eligible", next.workflowStatus());
        assertEquals("spec_off", next.reason());
    }

    /** A specification switched off while its run worked stops at what it wrote: nothing approves it, no plan run follows. */
    @Test
    void aSpecSwitchedOffWhileItsRunWorkedStopsThere() {
        WorkItemEvent running = started(profile("auto", "approve", "approve"));
        WorkItemEvent switched = running.decision(2, running.authority(), selected(profile("auto", "off", "approve")), "spec", "active",
                "phase_started", "POLICY_OBSERVED", null, running.progress());

        WorkItemEvent next = WorkDraftLifecycle.afterWritten(WorkDraftLifecycle.written(switched, WRITTEN, USAGE), OPENING);

        assertEquals("spec / not_eligible / spec_off", state(next));
        assertFalse(next.draft().specificationApproved());
        assertNull(next.gate());
    }

    /** The same for a plan switched off while its run worked: it is neither approved nor registered into build. */
    @Test
    void aPlanSwitchedOffWhileItsRunWorkedStopsThere() {
        WorkItemEvent atPlan = WorkDraftLifecycle.afterWritten(WorkDraftLifecycle.written(started(profile("auto", "auto", "approve")), WRITTEN, USAGE), OPENING);
        WorkItemEvent running = enter(atPlan, false, WorkItemLifecycle.EntryMode.DRAFT);
        WorkItemEvent switched = running.decision(2, running.authority(), selected(profile("auto", "auto", "off")), "plan", "active",
                "phase_started", "POLICY_OBSERVED", null, running.progress());
        WorkDraft.Written plan = new WorkDraft.Written(WorkDraftTest.stored('b'), "c".repeat(40), 2);

        WorkItemEvent next = WorkDraftLifecycle.afterWritten(WorkDraftLifecycle.written(switched, plan, USAGE), OPENING);

        assertEquals("plan / not_eligible / plan_off", state(next));
        assertFalse(next.draft().planApproved());
        assertNull(next.gate());
    }

    static final WorkDraftLifecycle.Revision REVISION = new WorkDraftLifecycle.Revision("TEST-operator", "TEST-revise-key", "TEST-note");

    WorkItemEvent specGate() {
        return WorkDraftLifecycle.afterWritten(WorkDraftLifecycle.written(started(profile("auto", "approve", "approve")), WRITTEN, USAGE), OPENING);
    }

    /** Spec auto and plan approve: the plan run written, its result gate open. */
    WorkItemEvent planGate() {
        WorkItemEvent atPlan = WorkDraftLifecycle.afterWritten(WorkDraftLifecycle.written(started(profile("auto", "auto", "approve")), WRITTEN, USAGE), OPENING);
        WorkItemEvent running = enter(atPlan, false, WorkItemLifecycle.EntryMode.DRAFT);
        assertEquals("plan / active / phase_started", state(running));
        WorkDraft.Written plan = new WorkDraft.Written(WorkDraftTest.stored('b'), "c".repeat(40), 2);
        return WorkDraftLifecycle.afterWritten(WorkDraftLifecycle.written(running, plan, USAGE), OPENING);
    }

    /** Revise parks the item where the draft sweep looks, and starts nothing itself (decision 8). */
    @Test
    void reviseParksForTheSweep() {
        WorkItemEvent gated = specGate();

        WorkItemEvent next = WorkDraftLifecycle.revised(gated, REVISION);

        assertEquals("spec / awaiting_input / specification_to_write", state(next));
        assertEquals("DRAFT_REVISED", next.milestone());
        assertEquals("REJECTED", next.gate().state());
        assertEquals("TEST-note", next.gate().note());
        assertEquals("dashboard", next.gate().channel());
        assertEquals(1, next.draft().specificationRevisions());
        assertNull(next.draft().specification());
        assertEquals(gated.progress().attemptId(), next.progress().attemptId(), "no attempt is started");
        assertNotEquals("started", next.progress().attemptState());
        assertFalse(next.progress().reserved());
    }

    @Test
    void revisingThePlanParksAtPlanToWrite() {
        WorkItemEvent next = WorkDraftLifecycle.revised(planGate(), REVISION);

        assertEquals("plan / awaiting_input / plan_to_write", state(next));
        assertEquals(1, next.draft().planRevisions());
        assertNull(next.draft().plan());
        assertNotNull(next.draft().specification(), "the approved specification stays");
    }

    /** Approving a new specification would invalidate a plan made from the old one (§6.5); revising it discards that plan at once. */
    @Test
    void revisingTheSpecificationDiscardsThePlan() {
        WorkItemEvent gated = specGate();
        WorkItemEvent withPlan = gated.drafted(gated.draft().withPlan(WorkDraftTest.stored('d'), 1));

        WorkItemEvent next = WorkDraftLifecycle.revised(withPlan, REVISION);

        assertNull(next.draft().plan());
        assertNull(next.draft().specification());
    }

    @Test
    void theFourthRevisionIsRefused() {
        WorkItemEvent gated = specGate();
        WorkDraft used = new WorkDraft(WorkDraft.GENERATED, gated.draft().specification(), null, WorkDraft.MAX_REVISIONS, 0, false, false,
                gated.draft().baseCommit(), gated.draft().setupRevision());

        assertThrows(IllegalStateException.class, () -> WorkDraftLifecycle.revised(gated.drafted(used), REVISION));
    }

    @Test
    void onlyAResultGateCanBeRevised() {
        WorkItemEvent parked = admit(profile("auto", "approve", "approve"));

        assertThrows(IllegalStateException.class, () -> WorkDraftLifecycle.revised(parked, REVISION));
    }

    /** An approved plan waits in plan for its registration, which decides whether the item may build (A4). */
    @Test
    void approvingThePlanWaitsForRegistration() {
        WorkItemEvent next = WorkDraftLifecycle.approved(planGate());

        assertEquals("plan / awaiting_input / phase_completed", state(next));
        assertEquals("DRAFT_APPROVED", next.milestone());
        assertTrue(next.draft().planApproved());
        assertNull(next.preparation());
    }

    static WorkPreparation preparation(WorkDraft draft) {
        return new WorkPreparation(draft.specification(), draft.plan(), "main", draft.baseCommit(), "codex", "TEST-model",
                "system:draft@" + draft.setupRevision(), WorkPreparation.STEPS_BINDING, null, null, List.of("TEST-check"), 60);
    }

    @Test
    void aRegisteredPlanMovesToBuild() {
        WorkItemEvent approved = WorkDraftLifecycle.approved(planGate());

        WorkItemEvent next = WorkDraftLifecycle.registered(approved, preparation(approved.draft()));

        assertEquals("build / awaiting_input / artifacts_registered", state(next));
        assertEquals("DRAFT_REGISTERED", next.milestone());
        assertEquals(WorkPreparation.STEPS_BINDING, next.preparation().bindingVersion());
        assertEquals(approved.draft(), next.draft());
    }

    @Test
    void aPlanCannotRegisterBeforeItIsApproved() {
        WorkItemEvent gated = planGate();

        assertThrows(IllegalStateException.class, () -> WorkDraftLifecycle.registered(gated, preparation(gated.draft())));
    }

    private WorkItemEvent registeredItem() {
        WorkItemEvent approved = WorkDraftLifecycle.approved(planGate());
        return WorkStepLifecycle.planRegistered(WorkDraftLifecycle.registered(approved, preparation(approved.draft())), 3);
    }

    /** Regenerate (A7): a new generation that writes its specification and plan again, from an empty draft. */
    @Test
    void regenerateClearsTheDraftAndThePreparation() {
        WorkItemEvent registered = registeredItem();

        WorkItemEvent next = WorkDraftLifecycle.regenerated(registered);

        assertEquals(registered.generation() + 1, next.generation());
        assertEquals("intake / awaiting_input / regenerated", state(next));
        assertEquals("READMITTED", next.milestone(), "a land fix and the projection read a new generation from this milestone");
        assertEquals(WorkDraft.admitted(), next.draft());
        assertNull(next.preparation());
        assertNull(next.steps(), "the discarded plan's cursor goes with it");
        assertNull(next.gate());
    }

    @Test
    void aComposedItemCannotRegenerate() {
        WorkItemEvent composed = observe(1, selected(profile("auto", "auto", "auto")), null);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> WorkDraftLifecycle.regenerated(composed));

        assertEquals("regenerate_unavailable", refused.getMessage());
    }

    /** A resume that cannot trust the prepared base drops the preparation: a generated item then writes its draft again. */
    @Test
    void anUnpreparedDraftIsWrittenAgain() {
        WorkItemEvent unprepared = WorkDraftLifecycle.unprepared(registeredItem());

        assertNull(unprepared.preparation());
        assertEquals(WorkDraft.admitted(), unprepared.draft());
        assertNull(unprepared.steps(), "no cursor is left for a plan that no longer exists");
        assertNull(WorkDraftLifecycle.unprepared(observe(1, selected(profile("auto", "auto", "auto")), null)).draft(),
                "a composed item stays composed");
    }

    @Test
    void aRefusedRegistrationWaitsInPlan() {
        WorkItemEvent next = WorkDraftLifecycle.registrationRefused(WorkDraftLifecycle.approved(planGate()), "draft_setup_changed");

        assertEquals("plan / awaiting_input / draft_setup_changed", state(next));
        assertNull(next.preparation());
    }
}
