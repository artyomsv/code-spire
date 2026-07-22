-- The PR's own state (Open / Merged / Closed), distinct from the review-processing status.
-- Set from the open/close webhook events; a new review's PR is open.
ALTER TABLE review_status ADD COLUMN pr_state VARCHAR(16) NOT NULL DEFAULT 'OPEN';
