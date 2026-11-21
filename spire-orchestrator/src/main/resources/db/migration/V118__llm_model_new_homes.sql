-- An LLM provider is a connection; the review model is chosen where it is used
-- (docs/superpowers/specs/2026-11-20-llm-provider-without-model-design.md, section 7).
--
-- This script only adds the new homes and copies today's data into them, so code that still reads
-- llm_provider.model keeps working until V119 drops it:
--   the global default model  -> app_setting 'llm.default-model' (beside the default provider)
--   a repository's model      -> repository.review_llm_model (beside review_llm_provider_id)
-- Stop every orchestrator instance before upgrading: an older instance selects and inserts llm_provider.model.

ALTER TABLE repository ADD COLUMN review_llm_model VARCHAR(255);

UPDATE repository r
   SET review_llm_model = p.model
  FROM llm_provider p
 WHERE r.review_llm_provider_id = p.id;

-- The setting follows the default provider whether or not it is enabled: the review path reads the setting only
-- while a default provider is enabled, and a switched-off default switched back on keeps its model.
INSERT INTO app_setting (key, value, updated_at)
SELECT 'llm.default-model', model, now()
  FROM llm_provider
 WHERE is_default
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

-- The code writes providers without a model from here on; V119 drops the column.
ALTER TABLE llm_provider ALTER COLUMN model DROP NOT NULL;
