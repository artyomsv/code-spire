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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 8b: on an untrusted pull request a source's links count only when its author has push access
 * by the {@code /fix} rule, and the reviewer bot never counts. The permission fake answers with the real
 * {@link FixAuthorization#decide} over a per-author override and forge permission, so ALLOW, DENY and UNKNOWN go
 * through the rule production uses. Every fake overrides each method the path reaches (CLAUDE.md, saga test fakes).
 */
class ContextLinksTrustTest {

    private static final String REVIEW_ID = "TEST-review";
    private static final UUID REPOSITORY_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b8");
    private static final String BOT_ID = "TEST-bot-id";
    private static final String BOT_HANDLE = "TEST-bot";
    private static final String AUTHOR = "TEST-7";
    private static final String MAINTAINER = "TEST-101";

    private final List<String> events = new ArrayList<>();
    private final List<String> reads = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, RepositoryPermission.State> forge = new HashMap<>();
    private final Map<String, FixAuthorization.Override> overrides = new HashMap<>();
    private boolean untrusted = true;
    private String botId = BOT_ID;
    private boolean permissionFails;
    private boolean reviewerRegistered = true;
    private boolean registrationFails;
    private Optional<UUID> repositoryId = Optional.of(REPOSITORY_ID);

    @Test
    void aMaintainersCommentLinksAreKept() {
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of("TEST-9"), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(events.getFirst().startsWith("ContextLinksTrusted|"), events.toString());
        assertTrue(events.getFirst().contains("counted: @TEST-maintainer"), events.toString());
    }

    @Test
    void aCommentByAPersonWithoutPushAccessIsDropped() {
        forge.put("TEST-102", RepositoryPermission.State.CANNOT_PUSH);

        assertEquals(Set.of(), gather(comment("TEST-102", "TEST-outsider", "TEST-9")));
        assertEquals(List.of("ContextLinksSkipped|context links skipped: untrusted pull request, no trusted author; "
                + "not counted: @TEST-outsider (CANNOT_PUSH)"), events);
    }

    @Test
    void theReviewerBotsCommentIsDroppedWithoutAPermissionRead() {
        forge.put(BOT_ID, RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of(), gather(comment(BOT_ID, "TEST-renamed-bot", "TEST-9")));
        assertTrue(reads.isEmpty(), reads.toString());
        assertTrue(events.getFirst().contains("(REVIEWER_BOT)"), events.toString());
    }

    /** A handle can only exclude: a comment under the bot's handle is dropped even when its id is not the bot's. */
    @Test
    void aCommentUnderTheBotsHandleIsDropped() {
        forge.put("TEST-other-id", RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of(), gather(comment("TEST-other-id", BOT_HANDLE, "TEST-9")));
        assertTrue(events.getFirst().contains("(REVIEWER_BOT)"), events.toString());
    }

    @Test
    void anAuthorWithPushAccessKeepsTheDescriptionsLinks() {
        forge.put(AUTHOR, RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of("TEST-1"), gather(description(AUTHOR, "TEST-1")));
    }

    @Test
    void anAuthorWithoutPushAccessLosesTheDescriptionsLinks() {
        forge.put(AUTHOR, RepositoryPermission.State.CANNOT_PUSH);

        assertEquals(Set.of(), gather(description(AUTHOR, "TEST-1")));
    }

    @Test
    void anExplicitDenyDropsAnAuthorWithPushAccess() {
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);
        overrides.put(MAINTAINER, FixAuthorization.Override.DENY);

        assertEquals(Set.of(), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(events.getFirst().contains("(EXPLICIT_DENY)"), events.toString());
    }

    @Test
    void anExplicitAllowKeepsAnAuthorWithoutPushAccess() {
        forge.put(MAINTAINER, RepositoryPermission.State.CANNOT_PUSH);
        overrides.put(MAINTAINER, FixAuthorization.Override.ALLOW);

        assertEquals(Set.of("TEST-9"), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
    }

    @Test
    void anUnknownPermissionDoesNotCount() {
        forge.put(MAINTAINER, RepositoryPermission.State.UNKNOWN);

        assertEquals(Set.of(), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(events.getFirst().contains("(PERMISSION_UNAVAILABLE)"), events.toString());
    }

    @Test
    void aFailedPermissionReadDoesNotCountAndTheReviewGoesOn() {
        permissionFails = true;

        assertEquals(Set.of(), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(events.getFirst().contains("(PERMISSION_UNAVAILABLE)"), events.toString());
    }

    /** Without the bot's identity its comments cannot be told apart, so nobody counts. */
    @Test
    void anUnknownReviewerIdentityTrustsNobody() {
        botId = "";
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of(), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(reads.isEmpty(), reads.toString());
        assertTrue(events.getFirst().contains("(REVIEWER_IDENTITY_UNKNOWN)"), events.toString());
    }

    @Test
    void aReviewerWithNoRegistrationTrustsNobody() {
        reviewerRegistered = false;
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of(), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(events.getFirst().contains("(REVIEWER_IDENTITY_UNKNOWN)"), events.toString());
    }

    @Test
    void aReviewWithNoRepositoryTrustsNobody() {
        repositoryId = Optional.empty();
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of(), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(reads.isEmpty(), reads.toString());
        assertTrue(events.getFirst().contains("(REPOSITORY_UNAVAILABLE)"), events.toString());
    }

    /** A read fault before any permission read drops every author and still answers, so the review goes on. */
    @Test
    void anUnreadableReviewerTrustsNobodyAndTheReviewGoesOn() {
        registrationFails = true;
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);

        assertEquals(Set.of(), gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9")));
        assertTrue(events.getFirst().contains("(PERMISSION_UNAVAILABLE)"), events.toString());
    }

    @Test
    void oneReadPerDistinctAuthor() {
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);

        Set<String> links = gather(comment(MAINTAINER, "TEST-maintainer", "TEST-9"),
                comment(MAINTAINER, "TEST-maintainer", "TEST-10"));

        assertEquals(Set.of("TEST-9", "TEST-10"), links);
        assertEquals(List.of(MAINTAINER), reads);
    }

    @Test
    void anAuthorWithNoIdIsNotReadAndDoesNotCount() {
        assertEquals(Set.of(), gather(comment(null, "TEST-anon", "TEST-9")));
        assertTrue(reads.isEmpty(), reads.toString());
        assertTrue(events.getFirst().contains("(UNKNOWN_ACTOR)"), events.toString());
    }

    @Test
    void authorsPastTheCapAreNotRead() {
        List<LinkSource> sources = new ArrayList<>();
        for (int i = 0; i <= LinkAuthorTrust.MAX_AUTHORS; i++) {
            forge.put("TEST-a" + i, RepositoryPermission.State.CAN_PUSH);
            sources.add(comment("TEST-a" + i, "TEST-a" + i, "TEST-" + (100 + i)));
        }

        Set<String> links = gather(sources.toArray(LinkSource[]::new));

        assertEquals(LinkAuthorTrust.MAX_AUTHORS, reads.size());
        assertEquals(LinkAuthorTrust.MAX_AUTHORS, links.size());
        assertTrue(events.getFirst().contains("@TEST-a" + LinkAuthorTrust.MAX_AUTHORS + " (AUTHOR_LIMIT)"),
                events.toString());
    }

    /** Ten commenters the forge says are nobody, ahead of a collaborator, no longer take the collaborator's reads. */
    @Test
    void unassociatedCommentersDoNotCrowdOutACollaborator() {
        List<LinkSource> sources = new ArrayList<>();
        for (int i = 0; i < LinkAuthorTrust.MAX_AUTHORS; i++) {
            forge.put("TEST-n" + i, RepositoryPermission.State.CAN_PUSH);
            sources.add(commentBy("TEST-n" + i, LinkSource.Vouch.NOT_VOUCHED));
        }
        forge.put(MAINTAINER, RepositoryPermission.State.CAN_PUSH);
        sources.add(commentBy(MAINTAINER, LinkSource.Vouch.VOUCHED));

        Set<String> links = gather(sources.toArray(LinkSource[]::new));

        assertEquals(Set.of("TEST-" + MAINTAINER), links);
        assertEquals(List.of(MAINTAINER), reads);
        assertTrue(events.getFirst().contains("(NOT_ASSOCIATED)"), events.toString());
        assertTrue(!events.getFirst().contains("AUTHOR_LIMIT"), events.toString());
    }

    @Test
    void anUnassociatedAuthorWithAnAllowOverrideStillCounts() {
        overrides.put("TEST-allowed", FixAuthorization.Override.ALLOW);

        assertEquals(Set.of("TEST-TEST-allowed"), gather(commentBy("TEST-allowed", LinkSource.Vouch.NOT_VOUCHED)));
        assertEquals(List.of("TEST-allowed"), reads);
    }

    /** The allowed author reads before the forge-vouched ones, so a full cap of vouched authors cannot displace it. */
    @Test
    void anAllowedAuthorReadsBeforeTheVouchedOnes() {
        List<LinkSource> sources = new ArrayList<>();
        for (int i = 0; i < LinkAuthorTrust.MAX_AUTHORS; i++) {
            forge.put("TEST-v" + i, RepositoryPermission.State.CAN_PUSH);
            sources.add(commentBy("TEST-v" + i, LinkSource.Vouch.VOUCHED));
        }
        overrides.put("TEST-allowed", FixAuthorization.Override.ALLOW);
        sources.add(commentBy("TEST-allowed", LinkSource.Vouch.NOT_VOUCHED));

        Set<String> links = gather(sources.toArray(LinkSource[]::new));

        assertTrue(links.contains("TEST-TEST-allowed"), links.toString());
        assertTrue(reads.contains("TEST-allowed"), reads.toString());
        assertEquals(LinkAuthorTrust.MAX_AUTHORS, reads.size());
    }

    @Test
    void thePullRequestsAuthorReadsBeforeVouchedCommenters() {
        List<LinkSource> sources = new ArrayList<>();
        for (int i = 0; i < LinkAuthorTrust.MAX_AUTHORS; i++) {
            forge.put("TEST-v" + i, RepositoryPermission.State.CAN_PUSH);
            sources.add(commentBy("TEST-v" + i, LinkSource.Vouch.VOUCHED));
        }
        forge.put(AUTHOR, RepositoryPermission.State.CAN_PUSH);
        sources.add(description(AUTHOR, "TEST-1"));

        gather(sources.toArray(LinkSource[]::new));

        assertTrue(reads.contains(AUTHOR), reads.toString());
    }

    @Test
    void aForgeThatSaysNothingKeepsTheFirstTenAuthors() {
        List<LinkSource> sources = new ArrayList<>();
        for (int i = 0; i <= LinkAuthorTrust.MAX_AUTHORS; i++) {
            forge.put("TEST-g" + i, RepositoryPermission.State.CAN_PUSH);
            sources.add(commentBy("TEST-g" + i, LinkSource.Vouch.UNKNOWN));
        }

        gather(sources.toArray(LinkSource[]::new));

        assertEquals(LinkAuthorTrust.MAX_AUTHORS, reads.size());
        assertTrue(!reads.contains("TEST-g" + LinkAuthorTrust.MAX_AUTHORS), reads.toString());
        assertTrue(events.getFirst().contains("(AUTHOR_LIMIT)"), events.toString());
    }

    @Test
    void aSameRepositoryReviewKeepsItsLinksAndReadsNoPermission() {
        untrusted = false;

        Set<String> links = links().toGather(event().withLinkSources(List.of(comment("TEST-102", "TEST-x", "TEST-9"))));

        assertEquals(Set.of("TEST-1"), links);
        assertTrue(reads.isEmpty(), reads.toString());
        assertTrue(events.isEmpty(), events.toString());
    }

    private Set<String> gather(LinkSource... sources) {
        return links().toGather(event().withLinkSources(List.of(sources)));
    }

    private static DiffFetched event() {
        return new DiffFetched(REVIEW_ID, 7L, "abc123", 1, List.of("java"), 10, false, Set.of("TEST-1"), null, null);
    }

    private static LinkSource comment(String id, String handle, String link) {
        return new LinkSource(LinkSource.Kind.COMMENT, id, handle, Set.of(link));
    }

    private static LinkSource commentBy(String id, LinkSource.Vouch vouch) {
        return new LinkSource(LinkSource.Kind.COMMENT, id, id, Set.of("TEST-" + id), vouch);
    }

    private static LinkSource description(String id, String link) {
        return new LinkSource(LinkSource.Kind.PULL_REQUEST, id, "TEST-author", Set.of(link));
    }

    private ContextLinks links() {
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
