-- Verdict labels (docs/superpowers/specs/2026-11-24-verdict-labels-design.md, ADR-047 amended 2026-11-24).
--
-- verdict_labels: after a review is posted, the reviewer sets one "review: " label on the pull request from the
-- decided verdict (review: approved or review: changes-requested) and removes every other "review: " label. Off for
-- every repository that exists today and for every new one: an install that does not use the convention must not get
-- labels it never asked for. GitHub only; the other forges have no label call yet.
--
-- The work-item change triggers (V104) compare the whole repository row, so this column needs no trigger of its own.
ALTER TABLE repository ADD COLUMN verdict_labels BOOLEAN NOT NULL DEFAULT FALSE;

-- Why the last posted review's label call failed, or NULL when it did not (or labels are off). Written with each
-- CommentsPosted under the same commit guard as last_posted_commit, so a later successful label clears it; the
-- attention feed raises REVIEW_LABEL_FAILED for a row that carries one.
ALTER TABLE review_status ADD COLUMN label_failure TEXT;
