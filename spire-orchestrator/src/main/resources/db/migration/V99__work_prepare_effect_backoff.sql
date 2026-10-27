-- A remote read that did not answer before a draft claim (a tracker outage, a forge 502 on the base branch) leaves the
-- work_prepare_effect row pending instead of stopping the item. deferrals counts such reads in a row; retry_after is
-- when the dispatcher may read again (30 seconds, doubling). The fifth deferral stops the item with the read's reason.
ALTER TABLE work_prepare_effect ADD COLUMN deferrals INT NOT NULL DEFAULT 0;
ALTER TABLE work_prepare_effect ADD COLUMN retry_after TIMESTAMPTZ;
