package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.LinkSource;
import dev.codespire.contract.scm.RepositoryPermission;
import dev.codespire.orchestrator.factory.FixAuthorization;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 8b: on an untrusted pull request a source's links count only when its author has push access
 * by the {@code /fix} rule, and the reviewer bot never counts. The permission fake answers with the real
 * {@link FixAuthorization#decide} over a per-author override and forge permission, so ALLOW, DENY and UNKNOWN go
 * through the rule production uses. Every fake overrides each method the path reaches (CLAUDE.md, saga test fakes).
 */
class ContextLinksTrustTest extends LinkTrustFixture {

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
    @Test
    void aSameRepositoryReviewKeepsItsLinksAndReadsNoPermission() {
        untrusted = false;

        Set<String> links = links().toGather(event().withLinkSources(List.of(comment("TEST-102", "TEST-x", "TEST-9"))));

        assertEquals(Set.of("TEST-1"), links);
        assertTrue(reads.isEmpty(), reads.toString());
        assertTrue(events.isEmpty(), events.toString());
    }
}
