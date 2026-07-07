-- LLM model catalog (ADR-018) — the list of models an operator can pick when
-- registering an LLM provider, each with its token pricing so a review's real
-- token usage can be priced (roadmap item 11; review_status.cost_millicents was
-- already collected but always 0). Prices are millicents (1/100,000 dollar) per
-- 1,000,000 tokens — integers, as providers quote per-1M pricing (e.g. gpt-4o
-- input $2.50/1M = 250000). Operator-entered, never hardcoded (prices drift).

CREATE TABLE llm_model (
    id                                   UUID         PRIMARY KEY,
    type                                 VARCHAR(64)  NOT NULL,   -- openai | anthropic | gemini
    name                                 VARCHAR(255) NOT NULL,   -- wire model id, e.g. gpt-4o
    label                                VARCHAR(255) NOT NULL,   -- display name
    input_price_millicents_per_million   BIGINT       NOT NULL,
    output_price_millicents_per_million  BIGINT       NOT NULL,
    enabled                              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- global-unique wire name: cost lookup keys off the model name alone
    UNIQUE (name)
);
