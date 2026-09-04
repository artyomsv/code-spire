-- Which comment asked for a fix run, and the claim that stops one comment buying two.
--
-- The T2+T3 review deferred this here with its key already decided: the claim must be on the
-- COMMENT, not on (review_id, finding_ref). Those two are the cap's axes and the cap is a different
-- question -- "has this finding had too many runs" is meant to have a nonzero answer, and a second
-- genuine /fix after a failed run is exactly what the cap exists to permit up to a bound. Keying the
-- claim there would forbid it.
--
-- Without it, a redelivered ManualCommandReceived buys a second run and there is no symptom. The run
-- id is derived from the finding's thread plus FixRuns.nextAttempt, and nextAttempt COUNTS the rows
-- the first delivery wrote -- so the redelivery derives attempt 2, a different run id, and sails
-- through the ON CONFLICT (run_id) guard that catches every other duplicate. The one mechanism that
-- would have stopped it is the one the numbering defeats.
--
-- Nullable, and NOT part of the kind CHECK V54 added. A build run has no comment and never will;
-- writing a placeholder would put a value in the unique index below that means "no comment".
ALTER TABLE factory_run ADD COLUMN comment_id TEXT;

-- Partial, on the two conditions that make it meaningful: FIX rows, with a comment.
--
-- The saga reads before it writes, and that read is what produces the refusal an author can act on.
-- This index is the backstop for the case the read cannot cover -- two deliveries genuinely at once.
-- They should be impossible: cs.integration is keyed by review id, so both land on one partition and
-- one consumer, in order. "Should be impossible" is the reason it is a constraint rather than only a
-- query: if it ever fires, the record dead-letters and an operator sees it, which is the correct
-- direction for a duplicate SPEND. A silent second agent on the branch is not.
CREATE UNIQUE INDEX idx_factory_run_fix_comment
    ON factory_run (comment_id)
 WHERE kind = 'FIX' AND comment_id IS NOT NULL;
