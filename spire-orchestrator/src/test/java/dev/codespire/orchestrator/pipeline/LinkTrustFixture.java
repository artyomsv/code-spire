package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.review.LinkSource;
import dev.codespire.contract.scm.RepositoryPermission;
import dev.codespire.orchestrator.factory.FixAuthorization;
import dev.codespire.orchestrator.factory.FixPermissionService;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ProviderView;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixture of the link-author trust tests (harness reviews, step 8b): the fakes, the knobs and the helpers
 * that build sources and gather links. Every fake overrides each method the path reaches (CLAUDE.md, saga test fakes).
 */
abstract class LinkTrustFixture {

    static final String REVIEW_ID = "TEST-review";
    static final UUID REPOSITORY_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b8");
    static final String BOT_ID = "TEST-bot-id";
    static final String BOT_HANDLE = "TEST-bot";
    static final String AUTHOR = "TEST-7";
    static final String MAINTAINER = "TEST-101";

    final List<String> events = new ArrayList<>();
    final List<String> reads = Collections.synchronizedList(new ArrayList<>());
    final Map<String, RepositoryPermission.State> forge = new HashMap<>();
    final Map<String, FixAuthorization.Override> overrides = new HashMap<>();
    boolean untrusted = true;
    String botId = BOT_ID;
    boolean permissionFails;
    boolean reviewerRegistered = true;
    boolean registrationFails;
    Optional<UUID> repositoryId = Optional.of(REPOSITORY_ID);

    Set<String> gather(LinkSource... sources) {
        return links().toGather(event().withLinkSources(List.of(sources)));
    }

    static DiffFetched event() {
        return new DiffFetched(REVIEW_ID, 7L, "abc123", 1, List.of("java"), 10, false, Set.of("TEST-1"), null, null);
    }

    static LinkSource comment(String id, String handle, String link) {
        return new LinkSource(LinkSource.Kind.COMMENT, id, handle, Set.of(link));
    }

    static LinkSource commentBy(String id, LinkSource.Vouch vouch) {
        return new LinkSource(LinkSource.Kind.COMMENT, id, id, Set.of("TEST-" + id), vouch);
    }

    static LinkSource description(String id, String link) {
        return new LinkSource(LinkSource.Kind.PULL_REQUEST, id, "TEST-author", Set.of(link));
    }

    ContextLinks links() {
        ContextLinks links = new ContextLinks();
        links.targets = new FixTargets() {
            @Override
            public boolean isUntrustedReview(String reviewId) {
                return untrusted;
            }
        };
        links.projection = projection();
        links.trust = trust();
        return links;
    }

    private ReviewProjection projection() {
        return new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                events.add(type + "|" + detail);
            }

            @Override
            public Optional<UUID> repositoryIdOf(String reviewId) {
                return repositoryId;
            }
        };
    }

    private LinkAuthorTrust trust() {
        LinkAuthorTrust trust = new LinkAuthorTrust();
        trust.projection = projection();
        trust.accounts = new RepositoryAccounts() {
            @Override
            public Optional<ProviderView> registration(UUID repositoryId, ProviderRole role) {
                if (registrationFails) {
                    throw new IllegalStateException("TEST database unreachable");
                }
                return reviewerRegistered ? Optional.of(reviewer()) : Optional.empty();
            }
        };
        trust.permissions = new FixPermissionService() {
            @Override
            public FixAuthorization.Override overrideOf(UUID repositoryId, String actorId) {
                return overrides.get(actorId);
            }

            @Override
            public FixAuthorization.Decision authorize(UUID repositoryId, String actorId) {
                reads.add(actorId);
                if (permissionFails) {
                    throw new IllegalStateException("TEST forge unreachable");
                }
                RepositoryPermission.State state = forge.getOrDefault(actorId, RepositoryPermission.State.UNKNOWN);
                return FixAuthorization.decide(actorId, overrides.get(actorId), new RepositoryPermission(state, "TEST"));
            }
        };
        return trust;
    }

    private ProviderView reviewer() {
        return new ProviderView("TEST-account", "TEST", "github", "https://example.invalid", "bearer", null, true,
                botId, true, List.of(), null, BOT_HANDLE, null, null, null, null, "REVIEWER", null, null, List.of(),
                List.of());
    }
}
