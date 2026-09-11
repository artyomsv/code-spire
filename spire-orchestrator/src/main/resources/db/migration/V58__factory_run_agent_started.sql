-- started_at records queue time (DEFAULT now() at INSERT). Historic agent starts are unknown;
-- deliberately leave them NULL rather than backfilling an invented start time.
ALTER TABLE factory_run ADD COLUMN agent_started_at TIMESTAMPTZ;
