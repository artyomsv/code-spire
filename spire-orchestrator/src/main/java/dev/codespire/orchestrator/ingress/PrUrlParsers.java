package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.port.PrUrlParser;
import dev.codespire.contract.scm.PrCoordinates;
import dev.codespire.scm.bitbucket.BitbucketCloudPrUrlParser;
import dev.codespire.scm.github.GitHubPrUrlParser;
import dev.codespire.scm.gitlab.GitLabPrUrlParser;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * The orchestrator's composition point for URL parsing: each provider owns its
 * own {@link PrUrlParser}; this tries them in turn and returns the first match.
 * The shapes are mutually exclusive (distinct {@code /pull/}, {@code /pull-requests/},
 * {@code /-/merge_requests/} routes), so order only affects which is attempted
 * first, never correctness. Mirrors the per-provider switch wiring in
 * {@code ProviderClients} / {@code WorkerScmClients}.
 */
@ApplicationScoped
public class PrUrlParsers {

    private static final List<PrUrlParser> PARSERS = List.of(
            new GitLabPrUrlParser(),
            new GitHubPrUrlParser(),
            new BitbucketCloudPrUrlParser());

    public Optional<PrCoordinates> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return PARSERS.stream()
                .map(parser -> parser.parse(url))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }
}
