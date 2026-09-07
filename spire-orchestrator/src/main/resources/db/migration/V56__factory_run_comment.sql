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

-- Keyed on (review_id, comment_id), NOT on comment_id alone.
--
-- A comment id is the FORGE's own id and every ingress passes it straight through (GitHub
-- comment.id, GitLab noteId, Bitbucket comment.id). It is unique within one forge and nowhere
-- else. A deployment holding a GitHub and a GitLab provider, or two self-hosted GitLabs whose
-- note ids both start at 1, produces the same value for unrelated comments.
--
-- Unscoped, that collision does two things and both are wrong. A legitimate /fix is refused with
-- "this comment already started fix run <another workspace's run id>" -- a foreign id written
-- into THIS review's durable history. And in the race this index exists to backstop, the INSERT
-- raises 23505 and the record dead-letters AFTER pool.select() has already spent a rotation slot.
--
-- This does not weaken the decision recorded above. The claim is still on the COMMENT rather than
-- on (review_id, finding_ref): review_id here scopes the comment id to the forge it came from,
-- and adds no second axis a genuine repeat /fix could trip over.

-- Partial, on the two conditions that make it meaningful: FIX rows, with a comment.
--
-- The saga reads before it writes, and that read is what produces the refusal an author can act on.
-- This index is the backstop for the case the read cannot cover -- two deliveries genuinely at once.
-- They should be impossible: cs.integration is keyed by review id, so both land on one partition and
-- one consumer, in order. "Should be impossible" is the reason it is a constraint rather than only a
-- query: if it ever fires, the record dead-letters and an operator sees it, which is the correct
-- direction for a duplicate SPEND. A silent second agent on the branch is not.
CREATE UNIQUE INDEX idx_factory_run_fix_comment
    ON factory_run (review_id, comment_id)
 WHERE kind = 'FIX' AND comment_id IS NOT NULL;

-- And a FIX row must carry the comment that asked for it.
--
-- V54 already ties review_id and finding_ref to kind = 'FIX'. Leaving comment_id out admitted a
-- row the CAP counts and the CLAIM cannot see: a second /fix on that comment finds no claim, so
-- it buys a second run. `asFixFor` refuses a blank id, so no code reaches that state today --
-- which is the argument for putting the rule in the schema rather than in one writer that has to
-- keep being careful. One-directional on purpose: a BUILD run has no comment and never will, so
-- the biconditional V54 uses would be wrong here.
ALTER TABLE factory_run ADD CONSTRAINT factory_run_fix_names_its_comment
    CHECK (kind <> 'FIX' OR comment_id IS NOT NULL);
