package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Labels a pull request {@code review: ci-failed} when its checks failed and no review ran (review waits for CI,
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md). Nothing is posted: the label is the only mark on
 * the pull request, and it replaces every other {@code review: } label like a verdict label does.
 *
 * <p>Best effort, like the verdict label: a forge failure is logged and the command is acknowledged, so a label never
 * dead-letters a skip the orchestrator already recorded. The forge call is idempotent, so a redelivered command finds
 * the label already set. No claim is taken: the orchestrator sends this command once per skipped head.
 */
@ApplicationScoped
public class ChecksFailedLabel {

    private static final Logger LOG = Logger.getLogger(ChecksFailedLabel.class);

    @Inject
    WorkerScmClients scm;

    public void apply(LabelChecksFailed command) {
        try {
            LabelOutcome outcome = scm.forCommand(command).comments()
                    .setVerdictLabel(command.repo(), command.prId(), VerdictLabel.CI_FAILED);
            LOG.infof("review %s: label %s on %s#%d for failed checks on %s: %s", command.reviewId(),
                    VerdictLabel.CI_FAILED.name(), command.repo().full(), command.prId(), command.commit(), outcome);
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not set the label %s for failed checks on %s", command.reviewId(),
                    VerdictLabel.CI_FAILED.name(), command.commit());
        }
    }
}
