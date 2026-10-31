-- The LLM provider a repository's reviews and replies use (docs/superpowers/specs/2026-10-30-harness-reviews-design.md,
-- decision 1). Null means the global default, which is every repository that exists today.
--
-- No ON DELETE action: deleting a provider a repository names is refused in LlmProviderRegistry before any row
-- changes, and this key backs that refusal.
ALTER TABLE repository ADD COLUMN review_llm_provider_id UUID REFERENCES llm_provider(id);
