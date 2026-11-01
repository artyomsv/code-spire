package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** A review run's id is the same for the same call, so a redelivered dispatch is dropped by the worker's claim. */
class ReviewRunIdsTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    private static ReviewRunBinding call(ReviewRunBinding.Kind kind, String slot) {
        return new ReviewRunBinding("review::TEST-WS/TEST-REPO#7", kind, 7, HEAD, slot);
    }

    @Test
    void theSameReviewCommitAndSlotGiveTheSameId() throws NoSuchAlgorithmException {
        String first = ReviewRunIds.of(REPO, ScmType.GITHUB, call(ReviewRunBinding.Kind.REVIEW, "TEST-slot"));
        String again = ReviewRunIds.of(REPO, ScmType.GITHUB, call(ReviewRunBinding.Kind.REVIEW, "TEST-slot"));

        assertEquals(first, again);
        assertEquals("review-7-0123456789ab-" + digest("REVIEW|TEST-slot"), RunIds.parse(first).subject());
    }

    @Test
    void aNewSlotGivesANewId() {
        String first = ReviewRunIds.of(REPO, ScmType.GITHUB, call(ReviewRunBinding.Kind.REVIEW, "TEST-slot"));

        assertNotEquals(first, ReviewRunIds.of(REPO, ScmType.GITHUB, call(ReviewRunBinding.Kind.REVIEW, "TEST-slot-2")));
        assertNotEquals(first, ReviewRunIds.of(REPO, ScmType.GITHUB, call(ReviewRunBinding.Kind.RECONCILE, "TEST-slot")),
                "another kind of call on the same slot is another run");
    }

    @Test
    void theIdParsesBackWithRunIds() {
        RunIds.Parsed parsed = RunIds.parse(ReviewRunIds.of(REPO, ScmType.GITLAB, call(ReviewRunBinding.Kind.FOLLOWUP, "TEST-slot")));

        assertEquals(ScmType.GITLAB, parsed.scmType());
        assertEquals("TEST-WS", parsed.workspace());
        assertEquals("TEST-REPO", parsed.slug());
        assertEquals(1, parsed.attempt());
    }

    private static String digest(String text) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).substring(0, 8);
    }
}
