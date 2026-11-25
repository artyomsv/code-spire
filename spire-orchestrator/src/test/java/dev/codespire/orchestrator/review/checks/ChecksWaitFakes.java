package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.readmodel.ReviewProjection;

import java.util.ArrayList;
import java.util.List;

/** What the check-wait tests record, and the read model that records it; the real one opens a database. */
abstract class ChecksWaitFakes {

    final List<ActionCommand> emitted = new ArrayList<>();
    final List<RecordCommand> recorded = new ArrayList<>();
    final List<String> statuses = new ArrayList<>();
    final List<String> history = new ArrayList<>();
    final List<String> reads = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    /** The desired-label writes, as RunLabels and DesiredLabels were asked them. */
    final List<String> labelLog = new ArrayList<>();

    List<ActionCommand.FetchDiff> fetches() {
        return emitted.stream().filter(ActionCommand.FetchDiff.class::isInstance)
                .map(ActionCommand.FetchDiff.class::cast).toList();
    }

    List<ActionCommand.LabelChecksFailed> labels() {
        return emitted.stream().filter(ActionCommand.LabelChecksFailed.class::isInstance)
                .map(ActionCommand.LabelChecksFailed.class::cast).toList();
    }

    long skips() {
        return recorded.stream().filter(RecordCommand.SkipReview.class::isInstance).count();
    }

    static ChecksVerdict green() {
        return new ChecksVerdict(ChecksVerdict.Kind.GREEN, List.of());
    }

    static ChecksVerdict red(String... failed) {
        return new ChecksVerdict(ChecksVerdict.Kind.RED, List.of(failed));
    }

    ReviewProjection recordingProjection() {
        return new TestRepositoryProjection() {
            @Override
            public void updateStatus(String reviewId, String status) {
                statuses.add(status);
            }

            @Override
            public void updateStatus(String reviewId, String status, int stage) {
                statuses.add(status);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                history.add(type);
            }

            @Override
            public void setNote(String reviewId, String text) {
                notes.add(text);
            }

            /** The dashboard push after the store showed the review waiting. */
            @Override
            public void touch(String reviewId) {
            }

            @Override
            public boolean archived(String reviewId) {
                return false;
            }
        };
    }
}
