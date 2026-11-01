-- Harness reviews, step 5: the harness half of a repository's "Review with" setting
-- (docs/superpowers/specs/2026-10-30-harness-reviews-design.md). Which harness reviews its pull requests, on which
-- model and thinking level, and how the run pays. All four null means the API, on review_llm_provider_id or the global
-- default, which is every repository that exists today.
ALTER TABLE repository ADD COLUMN review_harness TEXT;
ALTER TABLE repository ADD COLUMN review_harness_model TEXT;
ALTER TABLE repository ADD COLUMN review_harness_effort TEXT;
ALTER TABLE repository ADD COLUMN review_harness_pay_with TEXT;

-- Whole or absent, and one "Review with" choice: a harness never sits beside an API provider. Each arm tests IS NULL or
-- IS NOT NULL before it compares, because a CHECK that evaluates to NULL passes. Blank is not present, for the reason
-- V54 gives. The effort may be null (the model's own default); the harness is checked against the deployment's agent
-- images by RepositoryRegistry, since the schema cannot see configuration.
ALTER TABLE repository ADD CONSTRAINT repository_review_harness_whole
    CHECK ((review_harness IS NULL AND review_harness_model IS NULL AND review_harness_effort IS NULL
                AND review_harness_pay_with IS NULL)
        OR (review_harness IS NOT NULL AND btrim(review_harness) <> ''
                AND review_harness_model IS NOT NULL AND btrim(review_harness_model) <> ''
                AND review_harness_pay_with IS NOT NULL AND review_harness_pay_with IN ('API_KEY', 'SUBSCRIPTION')
                AND review_llm_provider_id IS NULL));

-- Why a review's harness start was refused, in words, for its attention row (step 5). Null means none: every review
-- row written before this, and every review that ran or took the API path.
ALTER TABLE review_status ADD COLUMN harness_refusal TEXT;
