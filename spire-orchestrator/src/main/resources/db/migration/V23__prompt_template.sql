-- Operator-editable prompt overrides (global, one row per kind). Absence of a row => built-in
-- default. Not secret — no encryption. Reset = DELETE. See ADR-018 / prompt-management design.
CREATE TABLE prompt_template (
    kind        TEXT PRIMARY KEY,
    system_text TEXT        NOT NULL,
    body_text   TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
