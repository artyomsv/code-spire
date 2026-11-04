-- Harness reviews, step 7 (review of PR #371, code-quality/M-1): a turn is counted once per posted answer, ever.
--
-- The harness reply's worker resends FollowUpPosted until it knows both completion events went out, and a dead-lettered
-- one can be replayed after a later answer in the same thread. Comparing with the thread's last answer alone counted
-- A, B, A as three turns for two answers, which spends the turn cap early. One row per counted answer, written in the
-- same statement that raises the count (ReviewThreadView.bumpTurn), makes the count exact under any replay order.
CREATE TABLE review_thread_turn (
    review_id   TEXT         NOT NULL,
    thread_ref  TEXT         NOT NULL,
    comment_id  TEXT         NOT NULL,
    counted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, thread_ref, comment_id)
);

-- The answers already counted before this table existed: each thread's last one is known, so its replay counts nothing.
INSERT INTO review_thread_turn (review_id, thread_ref, comment_id)
SELECT review_id, thread_ref, last_comment_id FROM review_thread
WHERE turn_count > 0 AND last_comment_id IS NOT NULL
ON CONFLICT DO NOTHING;
