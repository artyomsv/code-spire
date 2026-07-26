-- Last credential-verification outcome per provider, so the attention panel can report a
-- credential the provider refused. Written by the Check endpoints, by provider save (which
-- already probes to resolve bot identity), and by the pipeline when a real review is
-- rejected with a 401.
--
-- All three columns are nullable with no backfill: existing rows are genuinely unchecked and
-- NULL says so. last_check_ok is deliberately three-valued -- NULL never checked, TRUE
-- passed, FALSE rejected -- so "unchecked" can never be mistaken for "failing". Only an
-- explicit FALSE raises a panel row.

ALTER TABLE scm_provider
    ADD COLUMN last_check_at    TIMESTAMPTZ,
    ADD COLUMN last_check_ok    BOOLEAN,
    ADD COLUMN last_check_error TEXT;

ALTER TABLE llm_provider
    ADD COLUMN last_check_at    TIMESTAMPTZ,
    ADD COLUMN last_check_ok    BOOLEAN,
    ADD COLUMN last_check_error TEXT;

ALTER TABLE context_provider
    ADD COLUMN last_check_at    TIMESTAMPTZ,
    ADD COLUMN last_check_ok    BOOLEAN,
    ADD COLUMN last_check_error TEXT;
