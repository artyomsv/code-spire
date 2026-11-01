package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The run id of a harness review run (harness reviews, step 4): deterministic in the review's pull request, the head it
 * reads, the kind of call and the call slot, so a redelivered dispatch derives the same id and the worker's
 * {@code run_claim} drops the second command.
 */
public final class ReviewRunIds {

    /** Twelve characters of the head: enough to tell heads apart in a list, short enough to read. */
    private static final int COMMIT_CHARS = 12;

    /** Eight hex characters of the slot digest: the slot is free text, so it is hashed rather than spelled. */
    private static final int DIGEST_CHARS = 8;

    private ReviewRunIds() {
    }

    /** {@code review-<prId>-<commit12>-<slotDigest>}, attempt 1, where the digest is 8 hex of SHA-256 over kind|slot. */
    public static String of(RepoRef repo, ScmType type, ReviewRunBinding binding) {
        String commit = binding.commit();
        String shortCommit = commit.substring(0, Math.min(COMMIT_CHARS, commit.length()));
        String subject = "review-" + binding.prId() + "-" + shortCommit + "-" + slotDigest(binding);
        return RunIds.of(type, repo.workspace(), repo.slug(), subject, 1);
    }

    private static String slotDigest(ReviewRunBinding binding) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((binding.kind().name() + "|" + binding.callSlot()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
