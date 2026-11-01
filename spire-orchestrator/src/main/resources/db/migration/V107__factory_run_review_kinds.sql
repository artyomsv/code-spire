-- Harness reviews, step 4: a review may run on a harness, as a factory run of one of three review kinds. The kind
-- names the review call the run answers, and the ledger already admits the three as charge kinds (V42), so its CHECK
-- is not touched here.
--
-- Kept in step with RunKind by hand, as V54 says of its own list: nothing enforces it.
ALTER TABLE factory_run DROP CONSTRAINT factory_run_kind_closed;
ALTER TABLE factory_run ADD CONSTRAINT factory_run_kind_closed
    CHECK (kind IN ('BUILD', 'FIX', 'SPEC', 'PLAN', 'REVIEW', 'RECONCILE', 'FOLLOWUP'));

-- A review run names the review it answers and no finding; a fix keeps V54's arm; every other kind names neither.
-- Three explicit arms for V54's reason: kind is NOT NULL, and a CHECK that evaluates to NULL passes. Blank is not
-- absent, for the reason V54 gives too.
ALTER TABLE factory_run DROP CONSTRAINT factory_run_fix_names_its_target;
ALTER TABLE factory_run ADD CONSTRAINT factory_run_fix_names_its_target
    CHECK ((kind = 'FIX' AND review_id IS NOT NULL AND btrim(review_id) <> ''
                         AND finding_ref IS NOT NULL AND btrim(finding_ref) <> '')
        OR (kind IN ('REVIEW', 'RECONCILE', 'FOLLOWUP') AND review_id IS NOT NULL AND btrim(review_id) <> ''
                         AND finding_ref IS NULL)
        OR (kind NOT IN ('FIX', 'REVIEW', 'RECONCILE', 'FOLLOWUP') AND review_id IS NULL AND finding_ref IS NULL));

-- The review call a review run answers, beside the review: its call slot (the key its one charge is recorded under),
-- the pull request and the review round it belongs to (the count of ReviewRequested in the review's stream when the
-- call was decided). base_commit holds the head it reads. A result that carries no binding (a failure) finds it here,
-- and a review artifact's binding must equal it. A supersede ends a round, so the cancel compares the round, never the
-- commit: a forced re-run keeps the commit.
ALTER TABLE factory_run ADD COLUMN review_call_slot TEXT;
ALTER TABLE factory_run ADD COLUMN review_pr_id BIGINT;
ALTER TABLE factory_run ADD COLUMN review_round INT;

-- Both directions again: a review run without its slot would be charged under no call, and a build carrying one would
-- be counted against a review call it never answered.
ALTER TABLE factory_run ADD CONSTRAINT factory_run_review_names_its_call
    CHECK ((kind IN ('REVIEW', 'RECONCILE', 'FOLLOWUP') AND review_call_slot IS NOT NULL
                AND btrim(review_call_slot) <> '' AND review_pr_id IS NOT NULL AND review_pr_id > 0
                AND review_round IS NOT NULL AND review_round > 0)
        OR (kind NOT IN ('REVIEW', 'RECONCILE', 'FOLLOWUP') AND review_call_slot IS NULL AND review_pr_id IS NULL
                AND review_round IS NULL));

-- The cancellation read: a review's live review runs, on a superseded head or a closed pull request.
CREATE INDEX factory_run_review_run_idx ON factory_run (review_id)
    WHERE kind IN ('REVIEW', 'RECONCILE', 'FOLLOWUP');
