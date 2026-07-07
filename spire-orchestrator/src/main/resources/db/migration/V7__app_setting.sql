-- Runtime-mutable application settings as key/value rows — the counterpart to
-- boot-time SPIRE_* config. First user: review.mode (observe|active), which the
-- Settings page toggles WITHOUT restarting the orchestrator. A key is absent
-- until an operator overrides it, so the reader falls back to its config default
-- (spire.review.mode); once written, the stored value wins.

CREATE TABLE app_setting (
    key        VARCHAR(64)  PRIMARY KEY,
    value      VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
