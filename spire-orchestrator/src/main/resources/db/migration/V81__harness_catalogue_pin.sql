-- M3.5 part M, review of PR #167: the exact image a harness's model list was read from. A run uses it
-- instead of the tag, so two workers holding different images under one tag cannot run a model list
-- that was read from the other. NULL for a row written before pins, and for an image not reached.
ALTER TABLE harness_catalogue ADD COLUMN pinned_image TEXT;
