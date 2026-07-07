-- The technical error behind a terminal failure (e.g. the LLM provider's rejection
-- message). Previously only logged; the read model kept a generic note, so the UI
-- couldn't say WHY a review failed. Stored encrypted at rest (like findings_json,
-- AAD = review_id) since a provider error can echo fragments of the reviewed diff.
ALTER TABLE review_status ADD COLUMN error_detail TEXT;
