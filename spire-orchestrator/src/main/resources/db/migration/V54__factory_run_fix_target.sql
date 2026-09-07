-- A fix run records what it is fixing (FR-F27) and dispatch counts against it (FR-F32).
--
-- Nothing joined a run to a review before this. That absence is what made BOTH halves of the
-- milestone uncomputable: the fix-chain cap has nothing to count, and "run cost on the pull request"
-- has no key to sum by. Three nullable columns, because every run M0 and M1 dispatched has no review
-- and never will -- backfilling one would be inventing a fact.

ALTER TABLE factory_run ADD COLUMN review_id   TEXT;
ALTER TABLE factory_run ADD COLUMN finding_ref TEXT;

-- What the run was dispatched to do. Not derived from the presence of review_id, because a column
-- that answers "what is this" by the absence of another column stops being readable the moment a
-- third case exists.
--
-- The constraint below is stricter than that reasoning needs: it forbids a non-FIX row from
-- carrying a review at all, so a SPEC or PLAN run that wants one (both kinds are already admitted
-- by llm_charge's own CHECK since V42) needs this constraint relaxed first. That is deliberate --
-- the strict form is what closes the blank-id hole below, and relaxing it is a decision worth
-- making on purpose when the first such run exists rather than leaving the door open for it now.
--
-- Defaulted rather than NOT NULL-without-default: every existing row IS a build run, and asserting
-- that is more honest than leaving them null and making every reader handle a case that has one
-- answer.
ALTER TABLE factory_run ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'BUILD';

-- Closed set, for the reason factory_run.status is: a typo'd literal in a writer would otherwise
-- pass compilation and produce a row no cap counts and no filter matches. The set agrees with the
-- factory half of llm_charge's kind CHECK (V42) today -- two independent literals in two files,
-- with nothing enforcing that they stay agreed. Said plainly, because an earlier draft of this
-- comment claimed they "cannot drift apart", which is a guarantee no mechanism here provides.
ALTER TABLE factory_run ADD CONSTRAINT factory_run_kind_closed
    CHECK (kind IN ('BUILD', 'FIX', 'SPEC', 'PLAN'));

-- A fix run must name what it fixes; anything else must not pretend to.
--
-- Both directions, because either alone permits a row that lies. Without the first a FIX row can
-- carry no target, and the per-finding cap silently stops counting it -- which is the cap failing
-- open, on the axis that exists to bound spend. Without the second a BUILD row can carry a finding
-- ref it has no relationship to, and the same cap counts a run that never addressed it.
-- Blank is not absent. '' IS NOT NULL is true in Postgres, so the biconditional this started as
-- admitted a FIX row whose ids were empty strings -- counted by neither cap, for any real id, so
-- the cap failed OPEN for exactly that row. And this schema already uses blank-not-null for
-- source_branch and dest_branch (V2), so a dispatcher copying a blank through is a plausible bug
-- rather than a hypothetical one.
--
-- Written as two explicit arms rather than an equality, because kind is NOT NULL and a CHECK that
-- evaluates to NULL passes.
ALTER TABLE factory_run ADD CONSTRAINT factory_run_fix_names_its_target
    CHECK ((kind = 'FIX' AND review_id IS NOT NULL AND btrim(review_id) <> ''
                         AND finding_ref IS NOT NULL AND btrim(finding_ref) <> '')
        OR (kind <> 'FIX' AND review_id IS NULL AND finding_ref IS NULL));

-- The two cap reads. Per-finding bounds repeated attempts at one stubborn finding; per-review bounds
-- the chain a fix-review-fix loop walks, which under ADR-040 stays inside one review because the fix
-- pushes to the branch the review already watches.
-- One index, not two. A (review_id, finding_ref) index already serves a review_id = ? lookup on
-- its leading column under the same predicate, so a second index on review_id alone would cost
-- writes and buy no reads.
CREATE INDEX factory_run_fix_finding_idx ON factory_run (review_id, finding_ref)
    WHERE kind = 'FIX';
