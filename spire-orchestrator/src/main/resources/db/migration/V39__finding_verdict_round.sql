-- When a finding was judged, not just that it was (P4 / ADR-027 review round).
--
-- The analytics tile "median rounds to fix" was computed as
-- percentile_cont(0.5) WITHIN GROUP (ORDER BY round) FILTER (WHERE verdict = 'RESOLVED')
-- which is the median round a resolved finding was RAISED in -- not how long it took to
-- fix. A finding raised in round 1 and fixed in round 4 contributed 1, so on any healthy
-- repository the tile read 1.0 forever, and read it confidently.
--
-- The round is not derivable after the fact: verdict_at is a timestamp, and rounds are
-- not evenly spaced in time. So it is recorded when the verdict lands.
--
-- Existing rows keep NULL. The query filters them out rather than treating NULL as zero
-- -- a finding judged before this column existed has an unknown duration, and averaging
-- it in as "fixed in the round it was raised" would bias the number toward the answer
-- the bug already gave.

ALTER TABLE review_finding ADD COLUMN verdict_round INT;

-- Distinct reviews behind a preference proposal, so an operator can tell ten dismissals
-- from ten teams apart from ten by one author in one pull request. Not a column -- it is
-- computed by the scan -- but recorded here because the evidence columns beside it are.
ALTER TABLE learned_preference ADD COLUMN evidence_reviews INT NOT NULL DEFAULT 0;
