-- Persist the SCM provider type on each review row so the dashboard badge is
-- the real registered type (github / gitlab / bitbucket-cloud / bitbucket-dc)
-- instead of being guessed from the PR URL host — which fails for self-hosted
-- GitLab/Bitbucket whose host contains none of those substrings. Backfilled as
-- '' for existing rows; the UI falls back to URL sniffing when it is empty.

ALTER TABLE review_status ADD COLUMN provider_type VARCHAR(64) NOT NULL DEFAULT '';
