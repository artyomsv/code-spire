-- Context-provider registry (CONTRACT §7/§8): the single owner of context-source
-- credentials (Jira today, Confluence next), mirroring llm_provider. The secret is
-- Tink-encrypted at rest (AAD bound to the row id) and NEVER returned by the API —
-- only resolved internally to broker a ContextCredential to the worker. One global
-- default, like the LLM provider (a single Jira per install for now).
CREATE TABLE context_provider (
    id            UUID PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    type          VARCHAR(64) NOT NULL,             -- jira
    base_url      TEXT NOT NULL,                    -- site root, e.g. https://acme.atlassian.net
    auth_kind     VARCHAR(16) NOT NULL,             -- basic (email + API token) | bearer (PAT)
    auth_username VARCHAR(255),                     -- account email for basic; null for bearer
    auth_secret   TEXT NOT NULL,                    -- Tink-encrypted API token / PAT
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    is_default    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ
);

-- At most one default context provider (partial unique index, as for llm_provider).
CREATE UNIQUE INDEX context_provider_single_default ON context_provider (is_default) WHERE is_default = TRUE;
