-- Which SCM account an operator is (P4 / FR-11 self-visible analytics, ADR-027).
--
-- Per-author analytics needs to know that the human signed in over OIDC is the same
-- human as review_status.author_id. Nothing links them today: /api/me returns the
-- OIDC principal and nothing else, and provider_author is a per-SCM PR-author
-- allowlist rather than an operator link.
--
-- ADMIN-MANAGED, and matching usernames automatically is ruled out rather than
-- deferred. A coincidental match between an OIDC preferred_username and an SCM
-- handle would show one person another person's performance data, and nothing in the
-- UI would look wrong -- a silent failure, about a named individual. That is the
-- class ADR-022 was built to prevent when it made cookie scoping a real mechanism
-- instead of a convention.
--
-- Keyed with provider_type because a bare providerUserId is not a person: the same
-- id on GitHub and GitLab is two unrelated humans, and one workspace name registered
-- on two SCMs is the collision this project has already been bitten by twice.

CREATE TABLE operator_identity (
    oidc_subject  TEXT         NOT NULL,
    provider_type VARCHAR(64)  NOT NULL,
    author_id     VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (oidc_subject, provider_type)
);

-- The read that authorizes a per-author request: given an SCM identity, whose is it?
CREATE INDEX idx_operator_identity_author ON operator_identity (provider_type, author_id);
