package dev.codespire.worker.adapters;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The repository's own {@code .codespire} rules, contributed as RULE context (EVENT-MODEL S4). */
class RulesContextProviderTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");

    private final RulesContextProvider provider = new RulesContextProvider();

    @Test
    void contributesTheRepositoryRulesAsARuleItem() {
        String rules = "Money is always in millicents.\nNever use var in Java.";

        ContextContribution contribution = provider.contribute(request(rules)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(RulesContextProvider.SOURCE, contribution.source());
        ContextItem item = contribution.items().get(0);
        assertEquals("RULE", item.kind());
        assertTrue(item.title().contains(".codespire"));
        assertTrue(item.body().contains("millicents"));
        assertNull(item.uri(), "rules have no external address to link to");
    }

    /**
     * Most repositories have no rules file, and that is not a miss — reporting ERROR would put a row
     * on the timeline and in {@code missingSources} for every repository that never opted in.
     */
    @Test
    void reportsEmptyRatherThanErrorWhenTheRepositoryHasNoRules() {
        ContextContribution contribution = provider.contribute(request(null)).toCompletableFuture().join();

        assertEquals(ContribStatus.EMPTY, contribution.status());
        assertTrue(contribution.items().isEmpty());
        assertFalse(provider.supports(request(null)));
        assertFalse(provider.supports(request("   ")), "a whitespace-only file is no rules at all");
    }

    /**
     * Truncating beats dropping: the first conventions in a file are the ones a team put first, and a
     * silently discarded file would be indistinguishable from a repository having none.
     */
    @Test
    void truncatesAnOversizedRulesFileInsteadOfDiscardingIt() {
        String oversized = "R".repeat(RulesContextProvider.MAX_CHARS + 500);

        ContextItem item = provider.contribute(request(oversized)).toCompletableFuture().join().items().get(0);

        assertTrue(item.body().length() <= RulesContextProvider.MAX_CHARS + 1,
                "clipped to the cap plus the ellipsis, was " + item.body().length());
        assertTrue(item.body().endsWith("…"), "truncation is visible, not silent");
    }

    private static ContextRequest request(String rules) {
        return new ContextRequest("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                Set.of(), Set.of(), ScmType.GITHUB, rules);
    }
}
