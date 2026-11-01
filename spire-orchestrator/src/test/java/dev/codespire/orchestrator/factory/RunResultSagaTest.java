package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.review.run.ReviewRunBindings;
import dev.codespire.orchestrator.review.run.ReviewRunCharges;
import dev.codespire.orchestrator.review.run.ReviewRunResults;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunResultSagaTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    private static class RecordingProjection extends FactoryRunProjection {
        @Override
        protected void push(String runId) {
            // apply is recorded below; the fake must never open a live-feed database read.
            throw new AssertionError("unexpected live broadcast");
        }

        final List<RunResult> applied = new ArrayList<>();

        @Override
        public void apply(RunResult result) {
            applied.add(result);
        }

        RunKind kind = RunKind.BUILD;

        ReviewRunBinding binding;

        /** The saga reads the run's kind to route a review result; the real method reads factory_run. */
        @Override
        public Optional<RunKind> kindOf(String runId) {
            return Optional.of(kind);
        }

        /** Overridden for the same reason: a review result's binding is read from the row. */
        @Override
        public Optional<ReviewRunBinding> reviewBindingOf(String runId) {
            return Optional.ofNullable(binding);
        }
    }

    /** Records what the review charger was handed; the real one reads the run's row and writes the ledger. */
    private static class RecordingReviewCharges extends ReviewRunCharges {
        final List<RunResult> charged = new ArrayList<>();
        final List<ReviewRunBinding> bindings = new ArrayList<>();

        @Override
        public void record(RunResult result, ReviewRunBinding binding) {
            charged.add(result);
            bindings.add(binding);
        }
    }

    /** Records what reached the review-run port. */
    private static class RecordingReviewResults implements ReviewRunResults {
        final List<RunResult> accepted = new ArrayList<>();

        @Override
        public void accept(RunResult result, ReviewRunBinding binding) {
            accepted.add(result);
        }
    }

    /** Records what was charged, so the saga's own wiring is asserted rather than stubbed away. */
    private static class RecordingCharges extends RunCharges {
        final List<RunResult> charged = new ArrayList<>();

        @Override
        public void record(RunResult result) {
            charged.add(result);
        }
    }

    private static RunResultSaga saga(RecordingProjection projection) {
        return saga(projection, new RecordingCharges());
    }

    private static RunResultSaga saga(RecordingProjection projection, RecordingCharges charges) {
        RunResultSaga saga = new RunResultSaga();
        saga.projection = projection;
        // Overridden deliberately rather than left null. An un-overridden method on a saga test
        // fake opens a real database connection from a plain unit test -- a trap this repository
        // has hit four times in one milestone.
        saga.charges = charges;
        // The review-run collaborators, overridden for the same reason: each real one reads factory_run.
        saga.reviewRuns = new ReviewRunBindings() {
            @Override public boolean acceptsBinding(RunResult result) { return true; }
        };
        saga.reviewCharges = new RecordingReviewCharges();
        saga.reviewResults = new RecordingReviewResults();
        // This fixture represents standalone runs; item lookup is asserted by the work bridge service tests.
        saga.workItems = new dev.codespire.orchestrator.work.WorkItemRunBridge() {
            @Override public boolean accept(RunResult result) { return false; }
        };
        // Standalone runs are no draft runs either. Both methods the saga reaches are overridden: the real ones read
        // work_prepare_effect, a database call from a plain unit test (CLAUDE.md, saga test fakes).
        saga.drafts = new dev.codespire.orchestrator.work.WorkDraftResults() {
            @Override public boolean acceptsBinding(RunResult result) { return true; }
            @Override public boolean accept(RunResult result) { return false; }
        };
        // Same reason, and the same trap arriving again with a new collaborator: RunCredentialFeedback
        // reads the run's row to find which pool member to mark, so leaving it null is an NPE and
        // leaving it real is a database call from a unit test.
        saga.credentials = new RunCredentialFeedback() {
            @Override
            public void reactTo(RunResult result) {
                // nothing: what this suite asserts is the saga's ordering, not the pool's health
            }
        };
        // The same trap, a third collaborator later: this one reads the run's row and then talks to
        // a forge, so a null field is an NPE and a real one is a database call and an HTTP call from
        // a plain unit test. It records the order instead, which is what this suite is about.
        saga.pullRequests = new FactoryPullRequests() {
            @Override
            public void propose(RunResult result) {
                // nothing by default: the proposal is asserted in its own test, and a fake that
                // wrote into the projection's list would change what every other test here reads
            }
        };
        return saga;
    }

    /**
     * The proposal reads the row the projection has just written — its branch, its base and the
     * task line stored at dispatch. Called first it would read the QUEUED row, whose pushed ref is
     * still null, and propose nothing for a run that had in fact pushed.
     */
    @Test
    void theProposalRunsAfterTheProjectionHasWrittenTheRow() {
        RecordingProjection projection = new RecordingProjection();
        List<Integer> appliedWhenProposed = new ArrayList<>();
        RunResultSaga saga = saga(projection, new RecordingCharges());
        // Named apart from the field it would otherwise shadow: inside the subclass below,
        // `projection` resolves to FactoryPullRequests' own injected field, not to this local.
        RecordingProjection recorder = projection;
        saga.pullRequests = new FactoryPullRequests() {
            @Override
            public void propose(RunResult result) {
                appliedWhenProposed.add(recorder.applied.size());
            }
        };

        saga.on(new RunResult.RunFinished("run::github:TEST-acme/app:s:1", "refs/heads/spire/s",
                List.of("src/Foo.java"), List.of(), Map.of("input", 10L), false, List.of()));

        assertEquals(List.of(1), appliedWhenProposed, "the row must exist before it is read");
    }

    @Test
    void everyReadableResultReachesTheProjection() {
        RecordingProjection projection = new RecordingProjection();
        RunResult started = new RunResult.RunStarted("run::github:TEST-acme/app:s:1", "unit-1");
        RunResult failed = new RunResult.RunFailed("run::github:TEST-acme/app:s:1", "WORKER_FAILED", "x", true, null, List.of());

        saga(projection).on(started);
        saga(projection).on(failed);

        assertEquals(List.of(started, failed), projection.applied);
    }
    @Test void itemResultsAreProjectedAndChargedBeforeTheirExclusiveContinuation() {
        RecordingProjection projection=new RecordingProjection();RecordingCharges charges=new RecordingCharges();RunResultSaga saga=saga(projection,charges);
        List<RunResult> continued=new ArrayList<>(),proposed=new ArrayList<>();
        saga.workItems=new dev.codespire.orchestrator.work.WorkItemRunBridge(){@Override public boolean accept(RunResult result){
            assertEquals(List.of(result),projection.applied);assertEquals(List.of(result),charges.charged);continued.add(result);return true;
        }};
        saga.pullRequests=new FactoryPullRequests(){@Override public void propose(RunResult result){proposed.add(result);}};
        var result=new RunResult.RunFinished(RUN_ID,"refs/heads/spire/TEST-item",List.of("TEST-file"),List.of(),Map.of("INPUT",1L),false, List.of());saga.on(result);
        assertEquals(List.of(result),continued);assertTrue(proposed.isEmpty(),"An associated result belongs exclusively to the item continuation");
    }

    /**
     * A specification or plan run (M4 slice 3) is projected and charged, and goes no further: it pushed nothing to
     * propose, and the work-run inbox has no row for it (slice 6 gives drafts their own).
     */
    @Test
    void anArtifactIsNeverProposedAsAPullRequest() {
        RecordingProjection projection = new RecordingProjection();
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        List<RunResult> continued = new ArrayList<>();
        List<RunResult> proposed = new ArrayList<>();
        saga.workItems = new dev.codespire.orchestrator.work.WorkItemRunBridge() {
            @Override
            public boolean accept(RunResult result) {
                continued.add(result);
                return false;
            }
        };
        saga.pullRequests = new FactoryPullRequests() {
            @Override
            public void propose(RunResult result) {
                proposed.add(result);
            }
        };
        RunResult artifact = new RunWorkArtifact(RUN_ID, new dev.codespire.contract.work.WorkDraftBinding(
                "TEST-item", 1, java.util.UUID.fromString("00000000-0000-4000-8000-000000000005"),
                dev.codespire.contract.work.WorkDraftBinding.Kind.SPEC, "d".repeat(64)),
                "TEST-specification", false, Map.of("INPUT", 3L), 2, List.of());

        saga.on(artifact);

        assertEquals(List.of(artifact), projection.applied);
        assertEquals(List.of(artifact), charges.charged);
        assertTrue(proposed.isEmpty(), "an artifact pushed nothing to propose");
        assertTrue(continued.isEmpty(), "a draft run has no work-run row to continue");
    }

    /**
     * A draft run's result (M4 slice 3) belongs to the draft inbox alone: after the projection and the charge, neither
     * the work-run bridge nor the pull request proposal sees it, a failure included.
     */
    @Test
    void aDraftResultGoesNoFurtherThanItsInbox() {
        RecordingProjection projection = new RecordingProjection();
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        List<RunResult> drafted = new ArrayList<>();
        List<RunResult> continued = new ArrayList<>();
        List<RunResult> proposed = new ArrayList<>();
        saga.drafts = new dev.codespire.orchestrator.work.WorkDraftResults() {
            @Override public boolean acceptsBinding(RunResult result) { return true; }
            @Override public boolean accept(RunResult result) {
                assertEquals(List.of(result), projection.applied);
                assertEquals(List.of(result), charges.charged);
                drafted.add(result);
                return true;
            }
        };
        saga.workItems = new dev.codespire.orchestrator.work.WorkItemRunBridge() {
            @Override public boolean accept(RunResult result) { continued.add(result); return false; }
        };
        saga.pullRequests = new FactoryPullRequests() {
            @Override public void propose(RunResult result) { proposed.add(result); }
        };
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of());

        saga.on(failed);

        assertEquals(List.of(failed), drafted);
        assertTrue(continued.isEmpty(), "a draft run has no work-run row");
        assertTrue(proposed.isEmpty(), "a draft run is never proposed");
    }

    /** A draft result whose binding is not the effect row's is neither projected nor charged nor applied. */
    @Test
    void aForeignDraftBindingStopsTheResult() {
        RecordingProjection projection = new RecordingProjection();
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        List<RunResult> drafted = new ArrayList<>();
        saga.drafts = new dev.codespire.orchestrator.work.WorkDraftResults() {
            @Override public boolean acceptsBinding(RunResult result) { return false; }
            @Override public boolean accept(RunResult result) { drafted.add(result); return true; }
        };

        saga.on(new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of()));

        assertTrue(projection.applied.isEmpty());
        assertTrue(charges.charged.isEmpty());
        assertTrue(drafted.isEmpty());
    }

    private static final ReviewRunBinding REVIEW_CALL =
            new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1);

    /**
     * A review run's result (harness reviews, step 4) goes to the review-run port and no further: it pushed nothing to
     * propose and has no work-run or draft row. A review-kind FAILURE is the discriminating case, because a review
     * artifact could also be stopped by its type alone.
     */
    @Test
    void aReviewArtifactNeverReachesThePullRequestStep() {
        RecordingProjection projection = new RecordingProjection();
        projection.kind = RunKind.REVIEW;
        projection.binding = REVIEW_CALL;
        RunResultSaga saga = saga(projection);
        List<RunResult> continued = new ArrayList<>();
        List<RunResult> proposed = new ArrayList<>();
        saga.drafts = new dev.codespire.orchestrator.work.WorkDraftResults() {
            @Override public boolean acceptsBinding(RunResult result) { return true; }
            @Override public boolean accept(RunResult result) { continued.add(result); return false; }
        };
        saga.workItems = new dev.codespire.orchestrator.work.WorkItemRunBridge() {
            @Override public boolean accept(RunResult result) { continued.add(result); return false; }
        };
        saga.pullRequests = new FactoryPullRequests() {
            @Override public void propose(RunResult result) { proposed.add(result); }
        };
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of());
        RunResult artifact = new RunReviewArtifact(RUN_ID, REVIEW_CALL, "TEST-findings", false, Map.of("INPUT", 5L), 2, List.of());

        saga.on(failed);
        saga.on(artifact);

        assertEquals(List.of(failed, artifact), ((RecordingReviewResults) saga.reviewResults).accepted);
        assertTrue(continued.isEmpty(), "a review run has no draft or work-run row");
        assertTrue(proposed.isEmpty(), "a review run is never proposed");
    }

    /**
     * A review result is charged by the review charger, with the binding its row stores, after the projection and the
     * run plane (which skips review kinds) and before the review-run port.
     */
    @Test
    void aReviewResultIsChargedByTheReviewChargerOnly() {
        List<String> order = new ArrayList<>();
        RecordingProjection projection = new RecordingProjection() {
            @Override public void apply(RunResult result) { order.add("project"); }
        };
        projection.kind = RunKind.RECONCILE;
        projection.binding = REVIEW_CALL;
        RunResultSaga saga = saga(projection, new RecordingCharges() {
            @Override public void record(RunResult result) { order.add("run-plane"); }
        });
        RecordingReviewCharges reviewCharges = new RecordingReviewCharges() {
            @Override public void record(RunResult result, ReviewRunBinding binding) {
                super.record(result, binding);
                order.add("review-charge");
            }
        };
        saga.reviewCharges = reviewCharges;
        saga.reviewResults = (result, binding) -> order.add("review-result");
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of());

        saga.on(failed);

        assertEquals(List.of(failed), reviewCharges.charged);
        assertEquals(List.of(REVIEW_CALL), reviewCharges.bindings, "a failure carries no binding; the row's is used");
        assertEquals(List.of("project", "run-plane", "review-charge", "review-result"), order);
    }

    /** A review artifact answering a call its row does not store is neither projected nor charged nor accepted. */
    @Test
    void aReviewArtifactForAnotherCallStopsTheResult() {
        RecordingProjection projection = new RecordingProjection();
        projection.kind = RunKind.REVIEW;
        projection.binding = REVIEW_CALL;
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        saga.reviewRuns = new ReviewRunBindings() {
            @Override public boolean acceptsBinding(RunResult result) { return false; }
        };

        saga.on(new RunReviewArtifact(RUN_ID, REVIEW_CALL, "TEST-findings", false, Map.of("INPUT", 5L), 2, List.of()));

        assertTrue(projection.applied.isEmpty());
        assertTrue(charges.charged.isEmpty());
        assertTrue(((RecordingReviewCharges) saga.reviewCharges).charged.isEmpty());
        assertTrue(((RecordingReviewResults) saga.reviewResults).accepted.isEmpty());
    }

    @Test
    void aPoisonRecordIsSkippedNotProjected() {
        // The never-throw deserializer yields null for a record it cannot read; it is already on
        // cs.dlq. Projecting null would be an NPE that dead-letters a second copy of the same record.
        RecordingProjection projection = new RecordingProjection();

        saga(projection).on(null);

        assertTrue(projection.applied.isEmpty());
    }

    @Test
    void everyTerminalResultIsCharged() {
        // The wiring, not the pricing. Until this existed the saga read a finished run, wrote its
        // status and dropped the usage on the floor -- so a deployment could run the factory all
        // day, spend real money, and the rolling window the spend cap reads never moved.
        //
        // A FAILED run is charged too: an agent can work for an hour and then have its push
        // rejected, and those tokens were bought.
        RecordingCharges charges = new RecordingCharges();
        RunResult finished = new RunResult.RunFinished(RUN_ID, "refs/heads/spire/x",
                List.of(), List.of(), Map.of("INPUT", 10L), false, List.of());
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "x", false, Map.of("INPUT", 5L), List.of());

        RunResultSaga saga = saga(new RecordingProjection(), charges);
        saga.on(finished);
        saga.on(failed);

        assertEquals(List.of(finished, failed), charges.charged);
    }

    @Test
    void aPoisonRecordIsNotCharged() {
        // Null reaches here from the never-throw deserializer. Charging it would be an NPE that
        // dead-letters a second copy of a record already on cs.dlq.
        RecordingCharges charges = new RecordingCharges();

        saga(new RecordingProjection(), charges).on(null);

        assertTrue(charges.charged.isEmpty());
    }

    @Test
    void theOutcomeIsProjectedBeforeTheChargeIsRecorded() {
        // The run's outcome is the fact an operator is waiting on, and the ledger write is
        // best-effort by design. Ordering the charge first would let a ledger outage delay a
        // terminal status that is already known.
        List<String> order = new ArrayList<>();
        RecordingProjection projection = new RecordingProjection() {
            @Override
            public void apply(RunResult result) {
                order.add("project");
            }
        };
        RecordingCharges charges = new RecordingCharges() {
            @Override
            public void record(RunResult result) {
                order.add("charge");
            }
        };

        saga(projection, charges).on(new RunResult.RunFinished(RUN_ID, "refs/heads/spire/x",
                List.of(), List.of(), Map.of("INPUT", 10L), false, List.of()));

        assertEquals(List.of("project", "charge"), order);
    }
}
