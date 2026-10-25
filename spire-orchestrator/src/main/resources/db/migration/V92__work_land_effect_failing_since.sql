-- When a land row first failed to read GitHub or to act as the factory, in its current run of failures
-- (M4 land, review of slice 4). A row before the merge call is handed to the operator six hours after this;
-- a row after it is never handed, because the merge may have happened, and only says why it still waits.
-- Any pass that moves the row clears it. Each failed pass also sets next_check_at, so a stuck row is read
-- once a minute instead of on every sweep and never crowds newer rows out of the drain.
ALTER TABLE work_land_effect ADD COLUMN failing_since TIMESTAMPTZ;
