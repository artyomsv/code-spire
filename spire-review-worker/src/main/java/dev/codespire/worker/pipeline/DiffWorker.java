package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.worker.adapters.WorkerScmClients;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.ScmApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * FetchDiff worker: pulls the diff via the DiffSource port and emits
 * METADATA ONLY (ADR-011 — diff content is never stored or shipped in events;
 * it is re-fetched by commit at generate time).
 */
@ApplicationScoped
public class DiffWorker {

    private static final Logger LOG = Logger.getLogger(DiffWorker.class);

    @Inject
    WorkerScmClients scm;

    @Inject
    ResultsEmitter results;

    public void fetchDiff(FetchDiff command) {
        try {
            DiffSource diffSource = scm.forCommand(command).diff();
            // The PR carries the title/branch/description the issue keys are parsed
            // from — fetched here (one idempotent GET) so GatherContext can drive the
            // context providers; the diff itself stays metadata-only (ADR-011).
            PullRequest pr = diffSource.fetchPullRequest(command.repo(), command.prId());
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());
            results.emit(new DiffFetched(
                    command.reviewId(), command.prId(), command.commit(),
                    diff.files().size(),
                    diff.files().stream().map(FilePatch::language).distinct().toList(),
                    approximateSize(diff.files()),
                    diff.truncated(),
                    dev.codespire.context.jira.JiraTicketKeys.candidates(
                            pr.title(), pr.sourceBranch(), pr.description()),
                    // URLs the Confluence provider narrows to its own host (Jira keys above; same sources).
                    List.copyOf(dev.codespire.context.confluence.ConfluenceLinks.candidates(
                            pr.title(), pr.sourceBranch(), pr.description()))));
        } catch (RuntimeException e) {
            // ScmApiException is the provider-neutral shape both adapters implement.
            if (e instanceof ScmApiException api && api.isNotFound()) {
                // Commit force-pushed away: the run is superseded — abandon quietly (CONTRACT §4).
                LOG.infof("Abandoning FetchDiff for %s: diff 404 — commit force-pushed away", command.reviewId());
                return;
            }
            fail(command, e);
        }
    }

    private void fail(FetchDiff command, RuntimeException e) {
        LOG.warnf(e, "FetchDiff failed for %s", command.reviewId());
        // retryable=true lets the orchestrator's ResultSaga re-run the pipeline under its
        // bounded retry budget (ADR-016); transient (5xx / 429 / I/O) -> retryable, else terminal.
        boolean retryable = e instanceof ScmApiException api
                ? api.status() >= 500 || api.isRateLimited()
                : e instanceof java.io.UncheckedIOException;
        results.emit(new ReviewFailed(command.reviewId(), command.commit(), "fetch-diff",
                e.getMessage(), retryable, 1));
    }

    private long approximateSize(List<FilePatch> files) {
        long size = 0;
        for (FilePatch file : files) {
            for (Hunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    size += line.content().length() + 1;
                }
            }
        }
        return size;
    }
}
