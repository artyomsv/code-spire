-- Why the system has not prepared this item yet (M3.5 part C).
--
-- The sweep that composes a task picks items sitting at spec/awaiting_input/specification_required. If a
-- failed attempt wrote its reason onto the ITEM, that predicate would stop matching and the item would
-- never be retried -- and reconciliation does not repair it, because identical policy, authority and
-- issue return the previous item unchanged (WorkItemLifecycle.reconcile) and "the repository gained
-- build defaults" is not one of those observations.
--
-- So preparation health lives beside the item rather than inside its history: the workflow reason keeps
-- saying what the WORKFLOW is waiting for, and this row says what the last attempt hit. It is per
-- generation, because re-admission starts the question again.
CREATE TABLE work_item_preparation_attempt (
    work_item_id TEXT        NOT NULL REFERENCES work_item(id),
    generation   BIGINT      NOT NULL,
    -- The rule that refused, in the same vocabulary the screens already translate.
    reason       TEXT        NOT NULL CHECK (btrim(reason) <> ''),
    attempts     INT         NOT NULL DEFAULT 1 CHECK (attempts > 0),
    -- Bounded backoff: the sweep waits longer after each failure instead of asking a forge, a tracker
    -- and a catalogue about the same item every few seconds for ever.
    last_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    retry_after  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (work_item_id, generation)
);
