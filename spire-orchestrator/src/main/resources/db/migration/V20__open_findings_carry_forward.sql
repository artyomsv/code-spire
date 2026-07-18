-- Reconciliation baseline carry-forward (ADR-019 refinement): the next follow-up
-- must reconcile against everything still OPEN, not just the latest round's new
-- findings. open_findings_json = current new findings + prior still-open/unchanged
-- findings (with their original thread refs). Encrypted, AAD = review_id.
ALTER TABLE review_status ADD COLUMN open_findings_json TEXT;
