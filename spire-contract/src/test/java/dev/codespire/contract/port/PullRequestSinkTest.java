package dev.codespire.contract.port;

import dev.codespire.contract.scm.PullRequestRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The shapes the pull-request port refuses to carry.
 *
 * <p>Each of these is a caller bug that a forge would otherwise report as an opaque 4xx, hours later
 * and one service away. The port is the point where the caller still exists.
 */
class PullRequestSinkTest {

    @Test
    void aPullRequestNeedsBothBranchesByName() {
        for (String blank : new String[] {"", "   "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PullRequestSink.NewPullRequest(blank, "main", "t", "b"), "head=" + blank);
            assertThrows(IllegalArgumentException.class,
                    () -> new PullRequestSink.NewPullRequest("spire/x", blank, "t", "b"), "base=" + blank);
        }
        for (String missing : new String[] {null}) {
            assertThrows(NullPointerException.class,
                    () -> new PullRequestSink.NewPullRequest(missing, "main", "t", "b"));
            assertThrows(NullPointerException.class,
                    () -> new PullRequestSink.NewPullRequest("spire/x", missing, "t", "b"));
        }
    }

    /**
     * A branch onto itself is refused HERE, not left to the forge.
     *
     * <p>Every forge refuses it, each with its own opaque message — GitHub's is about "no commits
     * between", which reads as "the agent changed nothing" and sends an operator to the wrong place
     * entirely. Refusing at construction makes it a caller bug where the caller still exists.
     */
    @Test
    void aPullRequestFromABranchOntoItselfIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new PullRequestSink.NewPullRequest("spire/x", "spire/x", "t", "b"));
    }

    @Test
    void aPullRequestNeedsATitleForTheForgesListView() {
        assertThrows(IllegalArgumentException.class,
                () -> new PullRequestSink.NewPullRequest("spire/x", "main", "  ", "b"));
    }

    /** An empty body is legal — a description is optional on every forge; a MISSING one is a bug. */
    @Test
    void anEmptyBodyIsAllowedButANullOneIsNot() {
        assertEquals("", new PullRequestSink.NewPullRequest("spire/x", "main", "t", "").bodyMd());
        assertThrows(NullPointerException.class,
                () -> new PullRequestSink.NewPullRequest("spire/x", "main", "t", null));
    }

    /**
     * <b>Zero is not a pull request number, and that is the ADR-023 rule reaching a JSON parse.</b>
     *
     * <p>Every forge numbers from 1. A zero here means a response field was absent and read back as a
     * primitive default — the same fabricated zero the cost ledger refuses, arriving through a parse
     * rather than through a ledger. Stored on {@code factory_run.pr_id}, it would address nothing and
     * look like a real row.
     */
    @Test
    void aPullRequestNumberStartsAtOne() {
        assertThrows(IllegalArgumentException.class, () -> new PullRequestRef(0, "https://x/1"));
        assertThrows(IllegalArgumentException.class, () -> new PullRequestRef(-1, "https://x/1"));
    }

    /**
     * The URL comes from the adapter, so it may not be absent.
     *
     * <p>It is the only value in this record a caller cannot rebuild: the number is universal, the
     * host is not. A blank one means some caller assembles a link from a hardcoded host, which is how
     * a self-hosted GitLab gets a github.com link.
     */
    @Test
    void aPullRequestNeedsAUrlAHumanCanOpen() {
        assertThrows(IllegalArgumentException.class, () -> new PullRequestRef(1, "  "));
        assertThrows(NullPointerException.class, () -> new PullRequestRef(1, null));
    }
}
