-- Read model for the operator UI (spire-ui): one row per review, plus a
-- per-review event log for the detail page. Projected from cs.integration,
-- cs.events and cs.results by ReviewProjection — rebuildable, not a source of
-- truth (the event_log remains that).

CREATE TABLE review_status (
    review_id       VARCHAR(512) PRIMARY KEY,
    workspace       VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL,
    pr_id           BIGINT       NOT NULL,
    title           TEXT         NOT NULL DEFAULT '',
    author          VARCHAR(255) NOT NULL DEFAULT '',
    author_id       VARCHAR(255) NOT NULL DEFAULT '',
    source_branch   VARCHAR(512) NOT NULL DEFAULT '',
    dest_branch     VARCHAR(512) NOT NULL DEFAULT '',
    commit_sha      VARCHAR(64)  NOT NULL DEFAULT '',
    html_url        TEXT         NOT NULL DEFAULT '',
    status          VARCHAR(32)  NOT NULL,
    stage           SMALLINT     NOT NULL DEFAULT 0,
    findings_count  INT          NOT NULL DEFAULT 0,
    findings_json   TEXT,
    model           VARCHAR(128),
    tokens_in       INT,
    tokens_out      INT,
    cost_millicents BIGINT,
    latency_ms      INT,
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_status_updated ON review_status (updated_at DESC);

CREATE TABLE review_event (
    id         BIGSERIAL    PRIMARY KEY,
    review_id  VARCHAR(512) NOT NULL,
    seq        INT          NOT NULL,
    lane       VARCHAR(16)  NOT NULL,
    type       VARCHAR(128) NOT NULL,
    detail     TEXT         NOT NULL DEFAULT '',
    at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_event_review ON review_event (review_id, seq);
