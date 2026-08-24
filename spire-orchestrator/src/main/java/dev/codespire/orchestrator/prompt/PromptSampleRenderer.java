package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.llm.PromptValidation;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.llm.PromptRenderer;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a candidate prompt template against a REAL review the deployment already has.
 *
 * <p>Deliberately not a bundled sample diff. A shipped sample is fabricated data rendered in the UI
 * as though it were real input; a real review's diff is, by definition, not. It is also the better
 * preview: an operator wants to see their template against their own code, and running the
 * production {@link PromptRenderer} makes token clipping and untrusted-data fencing visible, neither
 * of which the annotated preview ({@link PromptValidation#preview}) shows.
 *
 * <p>Makes no LLM call — one diff fetch (the piece the clipping demo depends on). The pull request's
 * description is fetched too, best-effort, the same live read {@code ReviewsResource#description}
 * already does; a description-fetch failure degrades to an honest marker rather than failing the
 * whole preview, since the diff is what this class exists to demonstrate. Context is NOT fetched —
 * it lives in the review worker's own schema behind its own URL prefix and session (ADR-022), so
 * reaching it would mean a cross-service HTTP call this admin-only preview has no business making.
 * It is annotated the same way {@link PromptValidation#preview} annotates an un-fillable slot.
 */
@ApplicationScoped
public class PromptSampleRenderer {

    private static final String CONTEXT_UNAVAILABLE = "«context not shown in preview»";
    private static final String DESCRIPTION_UNAVAILABLE = "«description not shown in preview»";
    private static final String NONE = "(none)";

    /** The review exists but its input could not be assembled — shown to the operator as the reason
     *  the panel fell back to the annotated preview, rather than an empty box. */
    public static class PromptSampleUnavailable extends RuntimeException {
        public PromptSampleUnavailable(String message) {
            super(message);
        }
    }

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewProviderResolver reviewProviders;

    @Inject
    ProviderClients clients;

    public PromptValidation.PromptPreview render(PromptKind kind, String system, String body, String reviewId) {
        ReviewDetail review = loadReview(reviewId);
        RepoRef repo = new RepoRef(review.workspace(), review.slug());
        ScmProvider provider = reviewProviders.resolveForReview(reviewId)
                .orElseThrow(() -> new PromptSampleUnavailable(
                        "No enabled provider for " + repo.full() + " — add one under Settings -> Providers."));

        String diffText = fetchDiffText(provider, repo, review.pr(), review.sha());
        Map<String, String> values = valuesFor(kind, review, provider, repo, diffText);

        Prompt prompt = PromptRenderer.render(new PromptTemplate(kind, system, body), values).prompt();
        return new PromptValidation.PromptPreview(prompt.system(), prompt.user());
    }

    private ReviewDetail loadReview(String reviewId) {
        ReviewIds.Parsed parsed = ReviewIds.parse(reviewId);
        return projection.loadDetail(parsed.repo().workspace(), parsed.repo().slug(), parsed.prId())
                .orElseThrow(() -> new NotFoundException("No review for " + reviewId));
    }

    /** Re-fetched by commit, never persisted (ADR-011) — the same rule the real pipeline follows. */
    private String fetchDiffText(ScmProvider provider, RepoRef repo, long prId, String commit) {
        Diff diff;
        try {
            diff = clients.diffSource(provider).fetchDiff(repo, prId, commit);
        } catch (RuntimeException e) {
            if (!(e instanceof ScmApiException api)) {
                throw e; // a genuine bug must surface, not be reported as "unavailable"
            }
            throw new PromptSampleUnavailable("Could not fetch the diff from " + provider.type()
                    + " (status " + api.status() + ").");
        }
        return DiffRenderer.render(diff.files());
    }

    /** Best-effort: the description lives on neither {@code review_status} nor {@code DiffFetched}
     *  (see {@code ReviewsResource#description}), so it is always a live read. Unlike the diff, its
     *  loss does not invalidate the preview — it degrades to an honest marker. */
    private String fetchDescriptionOrMarker(ScmProvider provider, RepoRef repo, long prId) {
        try {
            return clients.diffSource(provider).fetchPullRequest(repo, prId).description();
        } catch (RuntimeException e) {
            if (!(e instanceof ScmApiException)) {
                throw e;
            }
            return DESCRIPTION_UNAVAILABLE;
        }
    }

    private Map<String, String> valuesFor(PromptKind kind, ReviewDetail review, ScmProvider provider,
                                          RepoRef repo, String diffText) {
        List<ReviewDetail.FindingView> openFindings = projection.openFindingsFor(review.id());
        Map<String, String> values = new HashMap<>();
        switch (kind) {
            case REVIEW -> {
                values.put("pr_title", review.title());
                values.put("pr_description", fetchDescriptionOrMarker(provider, repo, review.pr()));
                values.put("context", CONTEXT_UNAVAILABLE);
                values.put("prior_findings", renderFindings(openFindings));
                values.put("diff", diffText);
            }
            case RECONCILE -> {
                values.put("prior_findings", renderFindings(openFindings));
                values.put("diff_kind", "Current diff (shown for preview purposes)");
                values.put("diff", diffText);
            }
            case FOLLOWUP -> {
                values.put("anchor", anchorFrom(openFindings, review.sha()));
                values.put("other_threads", renderFindings(openFindings));
                values.put("diff", diffText);
                values.put("thread", NONE);
            }
        }
        return values;
    }

    private static String renderFindings(List<ReviewDetail.FindingView> findings) {
        if (findings.isEmpty()) {
            return NONE;
        }
        StringBuilder out = new StringBuilder();
        for (ReviewDetail.FindingView f : findings) {
            out.append("- [").append(f.sev()).append("] ").append(f.loc())
                    .append(" — ").append(f.msg()).append('\n');
        }
        return out.toString();
    }

    /** The real {@code FollowUpPrompt} anchor format ("path line N (commit sha)"), built from the
     *  review's own first open finding — real data, never a fabricated location. */
    private static String anchorFrom(List<ReviewDetail.FindingView> findings, String commit) {
        if (findings.isEmpty()) {
            return NONE;
        }
        String loc = findings.get(0).loc();
        int lastColon = loc.lastIndexOf(':');
        String path = lastColon > 0 ? loc.substring(0, lastColon) : loc;
        String line = lastColon > 0 ? loc.substring(lastColon + 1) : "?";
        return path + " line " + line + " (commit " + commit + ")";
    }
}
