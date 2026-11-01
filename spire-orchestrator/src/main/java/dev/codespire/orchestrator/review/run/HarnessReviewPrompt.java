package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.llm.PromptTemplate;

/**
 * The prompt of a first review on a harness (harness reviews, step 5): the repository's REVIEW system text, a preamble
 * with the agent's working rules and the diff command, the locked review suffix, and a last line that asks for no
 * verdict. Pure and static.
 *
 * <p>Not built yet: the red phase of step 5 declares the signature so the tests compile.
 */
public final class HarnessReviewPrompt {

    private HarnessReviewPrompt() {
    }

    /**
     * The pull request a prompt names.
     *
     * @param targetBranch the pull request's target branch, kept in the checkout as {@code refs/spire-base/<target>}
     */
    public record ReviewTarget(String workspace, String slug, long prId, String commit, String targetBranch) {
    }

    public static String compose(PromptTemplate review, ReviewTarget target) {
        throw new UnsupportedOperationException("not built yet");
    }
}
