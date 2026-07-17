-- Link finding threads to their (path, line) and flag the summary thread, so the review
-- detail can nest a conversation under its finding and route the rest to General discussion.
-- Additive: existing rows keep NULL path/line and is_summary = FALSE.
ALTER TABLE review_thread ADD COLUMN path       TEXT;
ALTER TABLE review_thread ADD COLUMN line       INT;
ALTER TABLE review_thread ADD COLUMN is_summary BOOLEAN NOT NULL DEFAULT FALSE;
