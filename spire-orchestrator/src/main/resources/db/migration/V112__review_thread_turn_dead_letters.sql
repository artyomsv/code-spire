-- Harness reviews, step 7 (review of PR #371, code-quality/M-1): an ambiguous old replay never consumes a turn.
--
-- Before the receipt table, ResultSaga counted a turn and only then linked the answer's own row, so a FollowUpPosted that
-- failed between the two writes was counted, named by nothing durable (V110 and V111 cannot find it unless it was its
-- thread's last answer), and dead-lettered. When this runs, a FollowUpPosted waiting in the dead-letter queue may
-- therefore be counted or not, and nothing can tell which. It is treated as counted: a receipt here makes its replay
-- count nothing. In the uncounted case the thread is one turn short of its cap, so the bot may answer once more there;
-- the other choice would end a conversation a turn early with nobody able to see why.
--
-- The receipt goes under the conversation root, as ReviewThreadView.bumpTurn files it (rootOf). A payload that is not
-- valid JSON is skipped rather than failing the deploy (pg_input_is_valid, PostgreSQL 16+; deployments run 18.4).
INSERT INTO review_thread_turn (review_id, thread_ref, comment_id)
SELECT d.review_id, COALESCE(t.root_ref, d.thread_ref), d.comment_id
FROM (
    SELECT doc ->> 'reviewId' AS review_id, doc -> 'threadRef' ->> 'value' AS thread_ref, doc ->> 'commentId' AS comment_id
    FROM (
        SELECT CASE WHEN pg_input_is_valid(payload, 'jsonb') THEN payload::jsonb END AS doc
        FROM dlq_entry
        WHERE message_type = 'FollowUpPosted' AND status = 'pending'
    ) pending
) d
LEFT JOIN review_thread t ON t.review_id = d.review_id AND t.thread_ref = d.thread_ref
WHERE d.review_id IS NOT NULL AND d.thread_ref IS NOT NULL AND d.comment_id IS NOT NULL
ON CONFLICT DO NOTHING;
