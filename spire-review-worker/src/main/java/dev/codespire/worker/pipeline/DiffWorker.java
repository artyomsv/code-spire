package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.worker.adapters.RulesContextProvider;
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

    /** Extraction is credential-free, so it runs here rather than waiting for GatherContext. */
    @Inject
    dev.codespire.worker.adapters.WorkerContextReferences references;

    /** Code-reference extraction runs on the parsed diff itself — the ticket-key extraction above
     *  runs on PR metadata instead, and the two must never cross-feed (CodeReferences javadoc). */
    @Inject
    dev.codespire.worker.adapters.WorkerCodeReferences codeRefs;

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
                    // Every registered extractor's candidates, unioned. Which syntax belongs to which
                    // source is the extractor's business; providers narrow this set later.
                    references.referencesIn(pr.title(), pr.sourceBranch(), pr.description()),
                    // Read from the TARGET branch, never the reviewed commit: the head is written by
                    // the change under review, so taking rules from it would let a PR rewrite the
                    // reviewer's instructions in the same PR being reviewed.
                    repoRules(diffSource, command, pr),
                    // Metadata only, same as everything else on this event — changed paths and the
                    // identifiers a changed line mentions, never hunk text (ADR-011).
                    codeRefs.inDiff(diff)));
        } catch (RuntimeException e) {
            // ScmApiException is the provider-neutral shape both adapters implement.
            if (e instanceof ScmApiException api && api.isNotFound()) {
                // Commit force-pushed away: the run is superseded — abandon quietly (CONTRACT §4).
                LOG.infof("Abandoning FetchDiff for %s: diff 404 — commit force-pushed away (%s)",
                        command.reviewId(), e.getMessage());
                return;
            }
            fail(command, e);
        }
    }

    /**
     * The repository's own review rules, or null when it has none — which is the common case.
     *
     * <p>Never fails the review: rules are enrichment, and a repository whose rules could not be read
     * should still get an ordinary review rather than none. A provider that cannot serve the file at
     * all (the stub, or an adapter that has not implemented it) returns null from the port.
     */
    private static String repoRules(DiffSource diffSource, FetchDiff command, PullRequest pr) {
        if (pr.targetBranch() == null || pr.targetBranch().isBlank()) {
            return null;
        }
        try {
            return diffSource.fetchTextFileOnBranch(command.repo(), pr.targetBranch(),
                    RulesContextProvider.FILE);
        } catch (RuntimeException e) {
            LOG.debugf("No repository rules for %s: %s", command.reviewId(), e.getMessage());
            return null;
        }
    }

    private void fail(FetchDiff command, RuntimeException e) {
        LOG.warnf(e, "FetchDiff failed for %s", command.reviewId());
        // Terminal, not transient: retrying cannot shrink the PR. Which response carries this
        // is the adapter's to know — core used to match HTTP 406, which is one provider's
        // convention (another reports oversize as Diff.truncated and never raises at all).
        if (e instanceof ScmApiException api && api.isDiffTooLarge()) {
            results.emit(new ReviewFailed(command.reviewId(), command.commit(), "fetch-diff",
                    "PR diff exceeds the provider's diff-generation limit — the PR is too large "
                            + "to review as one unit; split it or exclude generated files",
                    false, 1));
            return;
        }
        // retryable=true lets the orchestrator's ResultSaga re-run the pipeline under its
        // bounded retry budget (ADR-016); transient (5xx / 429 / I/O) -> retryable, else terminal.
        // An open circuit means the provider is down right now — transient by definition, so it
        // classifies like a 503 and the saga re-drives on the scheduled backoff rather than failing
        // the review outright (which would turn one outage into a pile of manual re-runs).
        boolean retryable = e instanceof dev.codespire.worker.adapters.ProviderCircuits.CircuitOpenException
                || (e instanceof ScmApiException api
                        ? api.status() >= 500 || api.isRateLimited()
                        : e instanceof java.io.UncheckedIOException);
        boolean credentialRejected = e instanceof ScmApiException api && api.isUnauthorized();
        results.emit(new ReviewFailed(command.reviewId(), command.commit(), "fetch-diff",
                e.getMessage(), retryable, 1, credentialRejected));
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
