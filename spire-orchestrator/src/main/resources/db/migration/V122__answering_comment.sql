-- Which reply the "responding" mark is for (review of PR #457, code-quality/M-1).
--
-- answering is one flag per review, but a reply that goes unanswered (FollowUpDeclined) is about one comment. Without
-- this column, reply A's decline cleared the mark while reply B's answer was still being written. The ask that sets
-- answering now records its triggering comment here, and a result that names its triggering comment clears the mark
-- only when it is still this comment (one compare-and-clear UPDATE). A new run, an archive and a closed pull request
-- clear both columns. NULL with answering set is a row from before this column: any result clears it, as before.
--
-- Additive and nullable: an orchestrator that predates it neither reads nor writes it.
ALTER TABLE review_status ADD COLUMN answering_comment TEXT;
