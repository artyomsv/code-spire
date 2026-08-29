-- The durable record of what reviews actually found (P4 / ADR-027).
--
-- DATA-MODEL.md has specified a review_finding table since the beginning and no
-- migration ever created it, so findings have only ever lived inline on Kafka
-- integration events (short retention, ADR-014) and in review_status.findings_json,
-- which holds one overwritten round. The corpus P4 learns from was being discarded
-- continuously -- ADR-011 working exactly as designed, with a consequence nobody
-- had drawn.
--
-- Encryption split follows the house rule: coordinates and classification in clear
-- because the table exists to be GROUPed and aggregated server-side, message and
-- suggestion Tink-encrypted because they quote the source under review. The same
-- split review_thread and code_symbol already make.
--
-- No backfill. Rows accrue from the day this ships, the same honest shape as the
-- symbol index -- a salvage from posted_findings_json would give exactly one
-- unrepresentative round per review, with no verdicts, and rows that look like
-- history.

CREATE TABLE review_finding (
    id           BIGSERIAL    PRIMARY KEY,
    review_id    VARCHAR(512) NOT NULL,
    -- Which review round raised this. In the row rather than derived, because
    -- review_status overwrites per round and that amnesia is exactly what makes
    -- "did this get fixed" unanswerable. Sourced from ReviewRuns (which counts
    -- ReviewRequested), never from review_status.attempt -- that is the auto-retry
    -- counter and would give one paid review several round numbers.
    round        INT          NOT NULL,
    commit_sha   VARCHAR(64)  NOT NULL DEFAULT '',
    path         TEXT         NOT NULL,
    start_line   INT          NOT NULL,
    end_line     INT          NOT NULL,
    severity     VARCHAR(16)  NOT NULL,
    -- Nullable for real, not in theory: prompts are operator-customizable per
    -- repository (E16), so a customized REVIEW template never asks for it. Those
    -- rows degrade to severity-and-path grouping rather than failing.
    category     VARCHAR(32),
    origin       VARCHAR(16)  NOT NULL DEFAULT 'review',   -- review | conversation
    message      TEXT,                                     -- Tink-encrypted, AAD = review_id
    suggestion   TEXT,                                     -- Tink-encrypted, nullable
    -- Null until the finding is posted. Born at CommentsPosted, not at generation,
    -- so a finding that was generated and never posted keeps a null here -- which is
    -- a fact worth recording rather than a gap.
    thread_ref   TEXT,
    -- Null means NOT YET JUDGED, which is genuinely different from "judged and
    -- unchanged". Conflating them would count every never-reconciled finding as a
    -- dismissal and inflate the rate that drives learned-memory proposals.
    verdict      VARCHAR(16),
    verdict_at   TIMESTAMPTZ,
    suppressed_by BIGINT,                                  -- FK added with learned_preference (V38)
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Deliberately NO unique constraint over (review_id, round, path, start_line, category).
-- It would protect nothing it needs to: Postgres treats NULLs as distinct, so it
-- would fail to deduplicate exactly the uncategorized rows a customized prompt
-- produces; and it is simultaneously too strong, since two distinct findings of one
-- category on one line are legitimate model output and one would vanish silently.
-- Redelivery idempotency is the handler's job instead -- delete-then-insert every
-- row for (review_id, round) in one transaction, which is what a ReviewGenerated
-- redelivered inside the isReviewing window actually needs.

CREATE INDEX idx_review_finding_review ON review_finding (review_id, round);
CREATE INDEX idx_review_finding_created ON review_finding (created_at DESC);
-- The aggregation path: group by category/severity over a time window.
CREATE INDEX idx_review_finding_group ON review_finding (category, severity, created_at DESC);
-- The verdict-matching path: newest not-yet-judged row for a location.
CREATE INDEX idx_review_finding_loc ON review_finding (review_id, path, start_line, id DESC);
