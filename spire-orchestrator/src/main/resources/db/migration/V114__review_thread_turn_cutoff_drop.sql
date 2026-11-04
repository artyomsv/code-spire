-- Harness reviews, step 7 (review of PR #371, code-quality/M-1): the per-thread id cutoff is withdrawn.
--
-- V113 inferred which answers were old from each thread's greatest surviving answer id, but a thread's greatest
-- counted id is not its greatest surviving one: an answer counted before a delayed lower one, or one whose event was
-- replayed last, left last_comment_id below it, and a replay of the higher id counted again. The boundary now rides on
-- the event itself: a FollowUpPosted emitted since the receipts exist says countsTurn, one recorded before them does
-- not, and ResultSaga files the latter (ReviewThreadView.fileAnswer) without counting it, whatever path brings it back
-- and in whatever order. With every old event stopped there, the cutoff could only ever act on a new answer, and its
-- only possible effect was to lose a turn, so it goes. V110 to V113 are unchanged; the receipts they seeded stay.
DROP TABLE review_thread_turn_cutoff;
