-- LLM provider registry (ADR-018) — the LLM counterpart to scm_provider. Holds
-- the model + API credentials for OpenAI/Anthropic/Gemini, chosen in the Settings
-- UI instead of SPIRE_LLM_* env vars. The api_key is Tink-encrypted at rest (AAD
-- bound to the row id), never returned by the API. At most one row is the global
-- default (partial unique index); the orchestrator packs the default's config,
-- encrypted, onto each GenerateReview command (ADR-015-style brokering).

CREATE TABLE llm_provider (
    id           UUID             PRIMARY KEY,
    name         VARCHAR(255)     NOT NULL,
    type         VARCHAR(64)      NOT NULL,          -- openai | anthropic | gemini
    base_url     TEXT             NOT NULL,
    api_key      TEXT             NOT NULL,          -- Tink-encrypted
    model        VARCHAR(255)     NOT NULL,
    temperature  DOUBLE PRECISION NOT NULL DEFAULT 0.2,
    max_tokens   INT,                                -- null = provider default
    enabled      BOOLEAN          NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- At most one global default.
CREATE UNIQUE INDEX llm_provider_single_default ON llm_provider (is_default) WHERE is_default = TRUE;
