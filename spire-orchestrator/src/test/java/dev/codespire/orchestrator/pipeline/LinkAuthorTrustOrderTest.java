package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.LinkSource;
import dev.codespire.contract.scm.RepositoryPermission;
import dev.codespire.orchestrator.factory.FixAuthorization;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 8b: the order in which link authors are read. Authors the forge says are nobody are dropped
 * without a read unless an ALLOW override saves them, the pull request's author and ALLOW overrides read first, and a
 * forge that says nothing keeps the first ten authors.
 */
class LinkAuthorTrustOrderTest extends LinkTrustFixture {

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
}
