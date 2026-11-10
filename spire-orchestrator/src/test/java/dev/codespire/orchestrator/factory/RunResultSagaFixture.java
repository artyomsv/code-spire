package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.review.run.ReviewRunBindings;
import dev.codespire.orchestrator.review.run.ReviewRunCharges;
import dev.codespire.orchestrator.review.run.ReviewRunResults;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The saga tests' shared wiring: a {@link RunResultSaga} whose every collaborator is a recording fake, so no test
 * here opens a database or a forge, and the recording fakes themselves.
 */
final class RunResultSagaFixture {

    static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    static class RecordingProjection extends FactoryRunProjection {
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
    static class RecordingReviewCharges extends ReviewRunCharges {
        final List<RunResult> charged = new ArrayList<>();
        final List<ReviewRunBinding> bindings = new ArrayList<>();

        @Override
        public void record(RunResult result, ReviewRunBinding binding) {
            charged.add(result);
            bindings.add(binding);
        }
    }

    /** Records what reached the review-run port. */
    static class RecordingReviewResults implements ReviewRunResults {
        final List<RunResult> accepted = new ArrayList<>();

        @Override
        public void accept(RunResult result, ReviewRunBinding binding) {
            accepted.add(result);
        }
    }

    /** Records what was charged, so the saga's own wiring is asserted rather than stubbed away. */
    static class RecordingCharges extends RunCharges {
        final List<RunResult> charged = new ArrayList<>();

        @Override
        public void record(RunResult result) {
            charged.add(result);
        }
    }

    static RunResultSaga saga(RecordingProjection projection) {
        return saga(projection, new RecordingCharges());
    }

    static RunResultSaga saga(RecordingProjection projection, RecordingCharges charges) {
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
        fakeTheWorkCollaborators(saga);
        fakeTheRunEffects(saga);
        return saga;
    }

    private static void fakeTheWorkCollaborators(RunResultSaga saga) {
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
    }

    private static void fakeTheRunEffects(RunResultSaga saga) {
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
    }

    static final ReviewRunBinding REVIEW_CALL =
            new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1);

    private RunResultSagaFixture() {
    }
}
