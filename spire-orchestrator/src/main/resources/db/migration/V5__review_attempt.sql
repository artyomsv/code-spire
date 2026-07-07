-- Bounded auto-retry (C8): the number of pipeline runs for the current review.
-- 1 = first run; each retry after a transient failure increments it, capped by
-- spire.review.max-attempts, after which the review fails terminally instead of
-- stalling in REVIEWING. Reset to 1 whenever a review is (re)registered.

ALTER TABLE review_status ADD COLUMN attempt INT NOT NULL DEFAULT 1;
