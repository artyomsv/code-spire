-- One approved land attempt (M4 land, spec section 7.3, amendment 11).
--
-- A row is written in the same transaction as the PHASE_STARTED that enters land, and only when that
-- event carries the APPROVED land gate whose artifact is the execution head: effect_id is the land
-- attempt id, approved_head is that artifact, and nothing but that head is ever merged. The WorkLandSweep
-- drains it: it reads the pull request, waits for checks, merges, records WORK_ITEM_LANDED, then deletes
-- the branch.
--
-- 'uncertain' is committed BEFORE the merge call. A row found there was sent a merge whose answer may be
-- lost, so recovery only reads the pull request; it never sends the merge again until a read proved the
-- pull request still open at approved_head (ADR-043). A human takeover refuses only rows still before the
-- merge call; a row in uncertain, merged or cleaning keeps draining, because the merge may have happened.
--
-- 'fixing' and fix_payload belong to the land fix loop (slice 7b); this slice hands every code refusal to
-- the operator instead.
CREATE TABLE work_land_effect (
    effect_id     UUID        PRIMARY KEY,
    work_item_id  TEXT        NOT NULL,
    generation    BIGINT      NOT NULL,
    item_revision BIGINT      NOT NULL,
    pr_number     BIGINT      NOT NULL,
    approved_head TEXT        NOT NULL,
    state         TEXT        NOT NULL CHECK (state IN ('pending', 'checking', 'waiting_checks', 'merging', 'uncertain',
                                                        'merged', 'cleaning', 'done', 'handed', 'fixing', 'closed', 'refused')),
    reason        TEXT,
    -- Tink, AAD work-land-detail:<effect_id>: GitHub's message and the check summary, which may quote source.
    detail        BYTEA,
    -- Tink, AAD work-land-fix:<effect_id>: what a land fix run is given (slice 7b).
    fix_payload   BYTEA,
    merge_commit  TEXT,
    -- 'factory' when this effect merged, else the stable provider id GitHub names; never a login.
    merged_by     TEXT,
    method        TEXT        CHECK (method IS NULL OR method IN ('merge', 'squash', 'rebase')),
    -- The head the pull request was merged at when it was found merged; the branch is deleted only there.
    merged_head   TEXT,
    cleanup_tries INT         NOT NULL DEFAULT 0,
    note          TEXT,
    waiting_since TIMESTAMPTZ,
    next_check_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One recorded merge per generation and pull request, whichever observer saw it first.
CREATE UNIQUE INDEX work_land_effect_one_merge ON work_land_effect (work_item_id, generation, pr_number)
    WHERE merge_commit IS NOT NULL;

CREATE INDEX work_land_effect_item ON work_land_effect (work_item_id, generation);
