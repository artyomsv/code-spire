-- S8 conversational review: per-provider interaction level (nullable → inherit the global default),
-- the bot's resolved login (for @-mention matching), and lightweight per-thread state (turn count +
-- ownership). No conversation text is stored (ADR-011) — threads are re-fetched from the SCM on demand.
ALTER TABLE scm_provider ADD COLUMN conversation_level TEXT;
ALTER TABLE scm_provider ADD COLUMN bot_username TEXT;

CREATE TABLE review_thread (
    review_id       TEXT    NOT NULL,
    thread_ref      TEXT    NOT NULL,
    turn_count      INT     NOT NULL DEFAULT 0,
    last_comment_id TEXT,
    is_ours         BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (review_id, thread_ref)
);
