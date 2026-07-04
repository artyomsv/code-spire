-- Worker-owned (schema-per-service): comment posting is a non-idempotent
-- external side effect; the key is persisted BEFORE the call so at-least-once
-- redelivery or a crash-after-post never duplicates comments (ADR-013,
-- DATA-MODEL §5). comment_id NULL = crashed claim, reclaimable.
CREATE TABLE comment_idempotency (
    review_id  TEXT NOT NULL,
    commit     TEXT NOT NULL,
    anchor_key TEXT NOT NULL,           -- 'SUMMARY' or 'path:line:side'
    comment_id TEXT,                    -- filled after the successful post
    posted_at  TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, commit, anchor_key)
);
