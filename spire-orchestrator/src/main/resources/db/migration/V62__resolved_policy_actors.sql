-- Display observations never replace the stable identity used by policy.
ALTER TABLE provider_author ADD COLUMN observed_handle TEXT;
ALTER TABLE provider_author ADD COLUMN display_name TEXT;
ALTER TABLE provider_author ADD COLUMN resolved_at TIMESTAMPTZ;
ALTER TABLE provider_author ADD COLUMN refresh_failed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE scm_provider ADD COLUMN actor_policy_revision BIGINT NOT NULL DEFAULT 1;

CREATE SEQUENCE repository_fix_actor_revision_seq;
CREATE TABLE repository_fix_actor (
    repository_id UUID NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
    actor_id TEXT NOT NULL CHECK (actor_id <> ''),
    effect TEXT NOT NULL CHECK (effect IN ('ALLOW','DENY')),
    observed_handle TEXT,
    display_name TEXT,
    resolved_at TIMESTAMPTZ,
    refresh_failed BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT nextval('repository_fix_actor_revision_seq'),
    PRIMARY KEY (repository_id,actor_id)
);

-- Numeric GitHub/GitLab IDs have an unambiguous stable namespace. Other forges
-- need a stable author ID observed on this repository's actual review history.
-- Unresolved legacy strings remain visible for explicit re-resolution, never /fix authority.
INSERT INTO repository_fix_actor(repository_id,actor_id,effect)
SELECT ra.repository_id,pa.author,'ALLOW'
FROM repository_account ra JOIN scm_provider p ON p.id=ra.account_id
JOIN provider_author pa ON pa.provider_id=p.id
WHERE ra.role='REVIEWER' AND (
    (p.type IN ('github','gitlab') AND pa.author ~ '^[0-9]+$') OR EXISTS (
        SELECT 1 FROM review_status review
        WHERE review.repository_id=ra.repository_id AND review.author_id=pa.author
    )
);
