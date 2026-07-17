-- One row per LLM call in a review's lifetime (the review generation + every conversation follow-up),
-- so the dashboard can show request count, per-call cost, and the true total.
CREATE TABLE review_llm_call (
    id              UUID        PRIMARY KEY,
    review_id       TEXT        NOT NULL,
    kind            TEXT        NOT NULL,        -- 'review' | 'followup'
    model           TEXT,
    tokens_in       INT         NOT NULL DEFAULT 0,
    tokens_out      INT         NOT NULL DEFAULT 0,
    cost_millicents BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX review_llm_call_review_idx ON review_llm_call (review_id, created_at);
