-- The hosts a run's agent asked its egress proxy for (docs/superpowers/plans/2026-11-01-harness-reviews-2-run-egress.md).
-- One row per run, host and port: whether the proxy let it through, and when the result first reported it. Hosts and
-- ports only. The proxy log is agent-controlled text, so nothing else from it is kept; a corporate proxy's
-- credentials never reach this table.
--
-- This is how the hosts Codex really calls are measured in report mode before enforce is switched on, and how an
-- operator reads which host an enforce run was refused. The rows belong to their run and go with it.
CREATE TABLE run_egress (
    run_id        TEXT        NOT NULL REFERENCES factory_run(run_id) ON DELETE CASCADE,
    host          TEXT        NOT NULL CHECK (length(host) BETWEEN 1 AND 253),
    port          INT         NOT NULL CHECK (port BETWEEN 1 AND 65535),
    allowed       BOOLEAN     NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, host, port)
);
