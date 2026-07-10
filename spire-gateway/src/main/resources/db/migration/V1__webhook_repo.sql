-- Per-repository / per-org webhook registrations — OWNED BY THE GATEWAY
-- (schema-per-service). This is gateway configuration, not orchestrator data: the
-- orchestrator's pipeline never reads it (it resolves the SCM provider from the PR
-- payload's owner against its own scm_provider registry). The gateway verifies
-- inbound signatures against this table at the edge and never touches the
-- orchestrator schema.
--
-- A registration is scoped either to a single repo or to a whole org:
--   scope='repo', target='owner/repo'  -> accept only that repo (per-repo webhook)
--   scope='org',  target='owner'       -> accept any owner/* repo (one org webhook)
--
-- webhook_secret is Tink-encrypted under the dedicated webhook keyset. There is no FK
-- to scm_provider (another schema; it was never the resolution mechanism). Routing is
-- by webhook_key (the URL path segment), so a delivery maps to exactly one row.

CREATE TABLE webhook_repo (
    id              UUID         PRIMARY KEY,
    provider_type   VARCHAR(64)  NOT NULL,          -- github | gitlab | bitbucket-cloud
    scope           VARCHAR(16)  NOT NULL,          -- repo | org
    target          VARCHAR(255) NOT NULL,          -- owner/repo (repo scope) | owner (org scope)
    webhook_key     VARCHAR(64)  NOT NULL,          -- unguessable routing token (URL path segment)
    webhook_secret  TEXT         NOT NULL,          -- Tink-encrypted (dedicated webhook keyset); HMAC/token secret
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (webhook_key),
    UNIQUE (provider_type, scope, target)
);

-- The gateway's hot path is a lookup by webhook_key on every inbound delivery.
CREATE INDEX idx_webhook_repo_key ON webhook_repo (webhook_key);
