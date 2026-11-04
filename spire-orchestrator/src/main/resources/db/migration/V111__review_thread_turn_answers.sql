-- Harness reviews, step 7 (review of PR #371, code-quality/M-1): V110 recorded each thread's LAST counted answer; this
-- records every earlier one too, so a replay of any answer counted before the receipt table existed counts nothing.
--
-- FollowUpPosted records each bot answer as a review_thread row of its own, linked to its conversation root (V24's
-- root_ref). The same link is written for the turn-cap notice and the /finding replies, which are never turns; a receipt
-- for them is inert, since no FollowUpPosted ever names those comments.
--
-- Not recoverable: an answer whose count committed but whose own row did not (ResultSaga counts first, then links),
-- and that was not its thread's last answer. Nothing durable names it, so a replay of it would count once more. That
-- needed a failure between two writes of one handler before this migration ran; docs/UNVERIFIED.md records it.
--
-- Idempotent (ON CONFLICT DO NOTHING), so ReviewThreadTurnBackfillIT runs it again over seeded history.
INSERT INTO review_thread_turn (review_id, thread_ref, comment_id)
SELECT review_id, root_ref, thread_ref FROM review_thread
WHERE root_ref IS NOT NULL
ON CONFLICT DO NOTHING;
