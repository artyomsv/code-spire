-- Harness reviews, step 7 (review of PR #371, code-quality/M-1): no answer from before the receipts counts again.
--
-- V110 to V112 recover the old answers that left a trace, but an old answer can leave none: one counted before V24 had
-- no answer row of its own, and a dead letter may reach dlq_entry, or be replayed, only after this deploy. So the
-- boundary is enforced at run time instead. Comment ids only grow on every forge, and one thread's answers come from one
-- id sequence (review comments, issue comments, notes). Each thread records the greatest answer id it knew here; a later
-- FollowUpPosted with no receipt and a numeric id at or below it is an old answer, and ReviewThreadView.bumpTurn files
-- its receipt without counting it. A new answer is posted after this deploy, so its id is greater, and it counts.
--
-- Per thread, not per review: GitHub's review comments and issue comments are separate sequences, so a review-wide
-- greatest id could sit above a new summary-thread answer and stop it counting. An old answer that was never counted
-- counts nothing when replayed: a thread may get one more answer before its cap, never one fewer.
CREATE TABLE review_thread_turn_cutoff (
    review_id       TEXT     NOT NULL,
    thread_ref      TEXT     NOT NULL,
    max_comment_id  NUMERIC  NOT NULL,
    PRIMARY KEY (review_id, thread_ref)
);

INSERT INTO review_thread_turn_cutoff (review_id, thread_ref, max_comment_id)
SELECT review_id, thread_ref, max(numeric_id)
FROM (
    -- The cast sits inside the CASE, so a non-numeric id (GitHub's "review::" summary refs) is never cast.
    SELECT review_id, thread_ref,
           CASE WHEN comment_id ~ '^[0-9]{1,30}$' THEN comment_id::numeric END AS numeric_id
    FROM (
        SELECT review_id, thread_ref, last_comment_id AS comment_id FROM review_thread WHERE root_ref IS NULL
        UNION ALL
        SELECT review_id, root_ref, thread_ref FROM review_thread WHERE root_ref IS NOT NULL
    ) answers
) ids
GROUP BY review_id, thread_ref
HAVING max(numeric_id) IS NOT NULL;
