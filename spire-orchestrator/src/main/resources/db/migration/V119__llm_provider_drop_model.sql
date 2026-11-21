-- An LLM provider is a connection; the review model is chosen where it is used
-- (docs/superpowers/specs/2026-11-20-llm-provider-without-model-design.md, section 7). V118 added and filled the new
-- homes; the code now reads and writes only those, so the old column goes.
--
-- Stop every orchestrator instance before upgrading: an older instance selects llm_provider.model. There is no
-- rollback without a database backup.

-- One "Review with" choice, whole or absent (V109), now with the API model beside its provider. V109's two arms are
-- kept as they were (the effort may be null; blank is not present); the API arm adds the model, both null or both set
-- and not blank, and the harness arm refuses an API model beside the harness.
ALTER TABLE repository DROP CONSTRAINT repository_review_harness_whole;
ALTER TABLE repository ADD CONSTRAINT repository_review_harness_whole
    CHECK ((review_harness IS NULL AND review_harness_model IS NULL AND review_harness_effort IS NULL
                AND review_harness_pay_with IS NULL
                AND ((review_llm_provider_id IS NULL AND review_llm_model IS NULL)
                  OR (review_llm_provider_id IS NOT NULL AND review_llm_model IS NOT NULL
                          AND btrim(review_llm_model) <> '')))
        OR (review_harness IS NOT NULL AND btrim(review_harness) <> ''
                AND review_harness_model IS NOT NULL AND btrim(review_harness_model) <> ''
                AND review_harness_pay_with IS NOT NULL AND review_harness_pay_with IN ('API_KEY', 'SUBSCRIPTION')
                AND review_llm_provider_id IS NULL
                AND review_llm_model IS NULL));

ALTER TABLE llm_provider DROP COLUMN model;
