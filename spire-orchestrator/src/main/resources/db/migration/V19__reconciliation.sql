-- Reconciliation on follow-up commits (ADR-019): the read model keeps the last
-- POSTED run's snapshot (source for command-carried PriorRun) and the latest
-- reconciliation verdicts; threads gain a resolved flag.
ALTER TABLE review_status ADD COLUMN last_posted_commit VARCHAR(64);
ALTER TABLE review_status ADD COLUMN last_summary_comment_id TEXT;
ALTER TABLE review_status ADD COLUMN posted_findings_json TEXT;   -- encrypted, AAD = review_id
ALTER TABLE review_status ADD COLUMN reconciliation_json TEXT;    -- encrypted, AAD = review_id
ALTER TABLE review_thread ADD COLUMN resolved BOOLEAN NOT NULL DEFAULT FALSE;
