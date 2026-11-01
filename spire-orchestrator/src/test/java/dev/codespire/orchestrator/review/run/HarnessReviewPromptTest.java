package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prompt of a first review on a harness (harness reviews, step 5): the repository's own REVIEW system text first,
 * the pull request and the diff command, the locked findings contract unchanged, and never a request for a verdict.
 */
class HarnessReviewPromptTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    private static final String LAST_LINE = "Do not give a verdict. The verdict is decided from your findings.";

    private static final PromptTemplate REVIEW =
            new PromptTemplate(PromptKind.REVIEW, "TEST-system: review like the repository asks.", "TEST-body {{diff}}");

    private static final HarnessReviewPrompt.ReviewTarget TARGET =
            new HarnessReviewPrompt.ReviewTarget("TEST-WS", "TEST-REPO", 7L, HEAD, "TEST-main");

    @Test
    void thePromptCarriesTheTargetHeadAndDiffCommand() {
        String prompt = HarnessReviewPrompt.compose(REVIEW, TARGET);

        assertTrue(prompt.contains("Review pull request #7 in TEST-WS/TEST-REPO."), prompt);
        assertTrue(prompt.contains(HEAD), "the head commit the checkout holds");
        assertTrue(prompt.contains("refs/spire-base/TEST-main"), "the target branch's ref in the checkout");
        assertTrue(prompt.contains("git diff $(git merge-base refs/spire-base/TEST-main HEAD) HEAD"), prompt);
        assertTrue(prompt.contains("git show refs/spire-base/TEST-main:.codespire"),
                "rules are read from the target branch, never from the change under review");
    }

    /** The same findings contract the API review's answer is parsed against, so FindingsParser reads it unchanged. */
    @Test
    void thePromptCarriesTheLockedReviewSuffix() {
        String prompt = HarnessReviewPrompt.compose(REVIEW, TARGET);

        assertTrue(prompt.contains(PromptCatalog.lockedSystemSuffix(PromptKind.REVIEW)), prompt);
        assertTrue(prompt.contains("$SPIRE_ARTIFACT"), "the answer goes to the artifact file");
    }

    /** Code Spire decides the verdict from the findings (2A); the agent is never asked for one. */
    @Test
    void thePromptNeverAsksForAVerdict() {
        String prompt = HarnessReviewPrompt.compose(REVIEW, TARGET);

        assertFalse(prompt.contains("APPROVE"), prompt);
        assertFalse(prompt.contains("REQUEST_CHANGES"), prompt);
        assertTrue(prompt.strip().endsWith(LAST_LINE), prompt);
    }

    /** The operator's editable REVIEW system text leads; the template's body frames a pasted diff and is not used. */
    @Test
    void thePromptStartsWithTheRepositorysReviewSystemText() {
        String prompt = HarnessReviewPrompt.compose(REVIEW, TARGET);

        assertTrue(prompt.startsWith("TEST-system: review like the repository asks.\n\n"), prompt);
        assertFalse(prompt.contains("TEST-body"), "the body frames a pasted diff the agent does not get");
    }
}
