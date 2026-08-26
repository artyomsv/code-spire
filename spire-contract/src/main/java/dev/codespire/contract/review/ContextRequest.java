package dev.codespire.contract.review;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;

import java.util.Set;

/**
 * What the context aggregator asks every provider to resolve.
 *
 * <p>{@code references} is one neutral, recall-favouring set of candidates found in the PR's own
 * text — issue keys, page links, whatever a registered
 * {@link dev.codespire.contract.port.ContextReferenceSource} recognises. Each provider narrows it to
 * the entries it can actually resolve, so nothing outside a provider needs to know which syntax
 * belongs to which source.
 *
 * <p>{@code scmType} is the platform this review runs on. Some references are repo-relative — an
 * issue number means nothing without a repository AND a host, and the same {@code workspace/slug}
 * routinely exists on two platforms. A provider compares this against its own axis before resolving
 * such a reference against {@code repo}; core only carries the value. Null means the platform could
 * not be determined, in which case a provider needing it must decline.
 *
 * <p>{@code repoRules} is the repository's own {@code .codespire} file, already fetched. It arrives
 * as text rather than as something a provider retrieves because retrieval needs an SCM credential and
 * this aggregator is deliberately never given one — the same reason reference extraction runs at
 * diff-fetch. Null when the repository has no rules file.
 *
 * <p>{@code codeReferences} is the diff's own changed paths and identifiers (see
 * {@link CodeReferences}), carried separately from {@code references} rather than folded into it: a
 * code context provider works from a different vocabulary (file paths and symbols, not issue keys or
 * page links) and at a different volume, and {@code references} is deliberately recall-favouring in a
 * way that would flood a code-context provider fed tens to hundreds of identifiers per diff.
 */
public record ContextRequest(String reviewId,
                             RepoRef repo,
                             long prId,
                             String commit,
                             Set<String> references,
                             Set<String> expectedSources,
                             ScmType scmType,
                             String repoRules,
                             CodeReferences codeReferences) {

    public ContextRequest {
        references = references == null ? null : Set.copyOf(references);
        expectedSources = expectedSources == null ? null : Set.copyOf(expectedSources);
        codeReferences = codeReferences == null ? CodeReferences.empty() : codeReferences;
    }

    // Without code references — every existing construction site and any replayed record.
    public ContextRequest(String reviewId,
                          RepoRef repo,
                          long prId,
                          String commit,
                          Set<String> references,
                          Set<String> expectedSources,
                          ScmType scmType,
                          String repoRules) {
        this(reviewId, repo, prId, commit, references, expectedSources, scmType, repoRules,
                CodeReferences.empty());
    }
}
