-- The last good model catalog (ai-catalog/v1/catalog.yaml, read from spire.llm.catalog.url).
--
-- One row, always id 1. The document is the exact validated YAML; the app parses it into memory and
-- answers every model question from that copy, manual models (llm_model) first. Public data, not
-- encrypted. A new valid file replaces the row in one transaction and sends
-- pg_notify('llm_catalog_changed', sha256), so every orchestrator instance swaps to it.
--
-- last_success_at is the last 200 or 304 from the URL; last_error is a status code, an exception class
-- or the validation rule that failed, never text from the fetched body.
CREATE TABLE llm_catalog_state (
  id              SMALLINT PRIMARY KEY CHECK (id = 1),
  document        TEXT,
  sha256          VARCHAR(64),
  etag            VARCHAR(256),
  source          VARCHAR(16) CHECK (source IN ('bundled','remote')),
  url             TEXT,
  loaded_at       TIMESTAMPTZ,
  last_checked_at TIMESTAMPTZ,
  last_success_at TIMESTAMPTZ,
  last_error      TEXT,
  last_change     JSONB
);

INSERT INTO llm_catalog_state (id) VALUES (1);
