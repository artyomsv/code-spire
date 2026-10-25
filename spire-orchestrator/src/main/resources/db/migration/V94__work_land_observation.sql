-- A merge made by hand that GitHub's pull request read has not confirmed yet (M4 land, spec section 7.8).
--
-- The merge webhook is only a claim; WORK_ITEM_LANDED is recorded from the read. When that read fails (GitHub
-- unreachable, rate limited, no account to read with) or does not show the merge yet, the webhook leaves one row
-- here in the same transaction as its delivery receipt, and every land sweep reads the pull request again until the
-- read settles it. The row is deleted once settled, or once the item is retired, completed or in a newer generation.
-- Without it the webhook went to the dead-letter topic, which nothing drains, and the item waited on a merged PR.
CREATE TABLE work_land_observation (
    work_item_id  TEXT        NOT NULL,
    generation    BIGINT      NOT NULL,
    pr_number     BIGINT      NOT NULL,
    -- The first delivery that saw the merge; the landing decision is keyed by it, as from the webhook.
    delivery_id   TEXT        NOT NULL,
    -- merged_by from the webhook (a stable provider id, never a login); the read's when null.
    merged_by     TEXT,
    -- land_read_failed or merge_not_confirmed: why the last read did not settle it.
    reason        TEXT        NOT NULL,
    next_check_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_item_id, generation, pr_number)
);
