-- Review of PR #168: the harness channels now replay from their oldest record, so an answer can
-- arrive after a newer one. The catalogue keeps the answer to the NEWEST question, compared by when the
-- question was asked. NULL for a row written before this, which any timed answer replaces.
ALTER TABLE harness_catalogue ADD COLUMN asked_at TIMESTAMPTZ;
