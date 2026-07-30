-- When the operator acknowledged this review's current stuck/failed state.
--
-- The attention panel's rule is that a row is a condition true right now, and fixing the cause
-- removes it. That holds for configuration state -- a rejected credential, a missing LLM default --
-- but it is FALSE for a failed review: a review that failed stays failed, and there is nothing an
-- operator can fix to clear it. The original design hid that behind a 24-hour window, which is
-- expiry rather than resolution, and which would retire a failure nobody ever saw.
--
-- Acknowledging is the honest resolution for a past event, so these two row types -- and only these
-- two -- are dismissable. Deliberately compared against review_status.updated_at rather than being
-- a boolean: dismissing acknowledges the state the operator actually looked at, so a LATER failure
-- on the same review raises the row again. A boolean would silence that review permanently, which
-- is how a dismissable alert becomes wallpaper.
--
-- Nullable with no backfill: an existing failed review has not been acknowledged, and NULL says so.

ALTER TABLE review_status ADD COLUMN attention_ack_at TIMESTAMPTZ;
