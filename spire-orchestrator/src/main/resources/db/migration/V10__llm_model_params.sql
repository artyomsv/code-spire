-- Per-model API parameter profile (ADR-018). Different models accept different
-- request parameters — notably newer OpenAI reasoning models (o1/o3/gpt-5) reject
-- `max_tokens` (they require `max_completion_tokens`) and reject a custom
-- temperature. Rather than hardcode a dialect per model name in the worker, the
-- operator declares each model's profile here; it is brokered to the worker per
-- review. Existing rows default to the classic Chat Completions dialect, so
-- behaviour is unchanged until an operator marks a model as a reasoning model.

ALTER TABLE llm_model
    ADD COLUMN output_token_param   VARCHAR(32) NOT NULL DEFAULT 'MAX_TOKENS',   -- MAX_TOKENS | MAX_COMPLETION_TOKENS | NONE
    ADD COLUMN supports_temperature BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN reasoning_effort     VARCHAR(16),                                 -- low | medium | high | null
    ADD COLUMN extra_params         TEXT        NOT NULL DEFAULT '{}';           -- JSON object, passed through as-is
