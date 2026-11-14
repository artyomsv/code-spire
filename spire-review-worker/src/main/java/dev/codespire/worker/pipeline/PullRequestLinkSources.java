package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.review.LinkSource;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.PullRequestComment;
import dev.codespire.worker.adapters.WorkerContextReferences;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Who wrote each context link on an untrusted pull request (harness reviews, step 8b): the pull request's title,
 * branch and description under its author, then each comment under the comment's author. A source with no link is
 * left out. Only ids, handles and links leave here; comment text is read, scanned and dropped.
 *
 * <p>The orchestrator decides which authors count. This class decides nothing about trust, so a comment by the
 * reviewer bot is reported like any other and dropped there, where the decision is recorded.
 */
final class PullRequestLinkSources {

    private static final Logger LOG = Logger.getLogger(PullRequestLinkSources.class);

    private final WorkerContextReferences references;
    private final DiffSource source;

    PullRequestLinkSources(WorkerContextReferences references, DiffSource source) {
        this.references = references;
        this.source = source;
    }

    /**
     * The event with its link sources, for an untrusted pull request only: a same-repository one reads no comments
     * and costs no call.
     */
    DiffFetched attach(FetchDiff command, PullRequest pr, DiffFetched fetched) {
        return command.untrustedPullRequest() ? fetched.withLinkSources(of(pr)) : fetched;
    }

    List<LinkSource> of(PullRequest pr) {
        List<LinkSource> sources = new ArrayList<>();
        add(sources, new Written(LinkSource.Kind.PULL_REQUEST, pr.author(), LinkSource.Vouch.UNKNOWN),
                references.referencesIn(pr.title(), pr.sourceBranch(), pr.description()));
        for (PullRequestComment comment : comments(pr)) {
            add(sources, new Written(LinkSource.Kind.COMMENT, comment.author(), comment.vouch()),
                    references.referencesIn(comment.body()));
        }
        return sources;
    }

    /**
     * Fails soft: comments are enrichment, and a review whose comments could not be read still gets the pull
     * request's own source and an ordinary review. Only the failure's message is logged, never a comment.
     */
    private List<PullRequestComment> comments(PullRequest pr) {
        try {
            return source.fetchPullRequestComments(pr.repo(), pr.prId());
        } catch (RuntimeException e) {
            LOG.warnf("Could not read the comments of %s#%d for context links: %s",
                    pr.repo().full(), pr.prId(), e.getMessage());
            return List.of();
        }
    }

    private record Written(LinkSource.Kind kind, Author author, LinkSource.Vouch vouch) {
    }

    private static void add(List<LinkSource> sources, Written written, Set<String> links) {
        if (links.isEmpty()) {
            return;
        }
        Author author = written.author();
        String id = author == null ? null : author.providerUserId();
        String handle = author == null ? null : author.username();
        sources.add(new LinkSource(written.kind(), id, handle, links, written.vouch()));
    }
}
