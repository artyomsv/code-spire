package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.scm.bitbucket.BitbucketApiException;
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
    DiffSource diffSource;

    @Inject
    ResultsEmitter results;

    public void fetchDiff(FetchDiff command) {
        try {
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());
            results.emit(new DiffFetched(
                    command.reviewId(), command.prId(), command.commit(),
                    diff.files().size(),
                    diff.files().stream().map(FilePatch::language).distinct().toList(),
                    approximateSize(diff.files()),
                    diff.truncated()));
        } catch (BitbucketApiException e) {
            if (e.isNotFound()) {
                // Commit force-pushed away: the run is superseded — abandon quietly (CONTRACT §4).
                LOG.infof("Abandoning FetchDiff for %s: diff 404 — commit force-pushed away", command.reviewId());
                return;
            }
            fail(command, e);
        } catch (RuntimeException e) {
            fail(command, e);
        }
    }

    private void fail(FetchDiff command, RuntimeException e) {
        LOG.warnf(e, "FetchDiff failed for %s", command.reviewId());
        // TODO(P1.x): retry budget via SmallRye Fault Tolerance (ADR-013); one attempt for now.
        boolean retryable = e instanceof BitbucketApiException api ? api.status() >= 500
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
