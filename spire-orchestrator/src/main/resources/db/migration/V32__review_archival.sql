-- Deleting a review used to destroy its charge ledger, so real paid usage vanished with a row
-- removed for being clutter. Archival replaces that: nothing is deleted, NULL archived_at = live.
--
-- review_status.archived_at is written by archiving. llm_charge.archived_at is NOT: it is written
-- only by a future purge, in the same transaction that hard-deletes the review row. Stamping the
-- ledger at archive time would hide an archived review's cost from its OWN detail page, because the
-- per-review cost reads key on review_id alone and are the same reads that serve that page.

ALTER TABLE review_status ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE llm_charge    ADD COLUMN archived_at TIMESTAMPTZ;

-- The reviews list reads live rows ordered by recency; keep that path off the archived rows.
CREATE INDEX review_status_live_updated
    ON review_status (updated_at DESC) WHERE archived_at IS NULL;
