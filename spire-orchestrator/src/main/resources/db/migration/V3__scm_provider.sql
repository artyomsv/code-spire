-- SCM provider registry: connection + credentials for each registered SCM,
-- replacing the .env-based single-provider config. The auth secret (API token /
-- app password) is stored Tink-encrypted (CryptoService); a PR is matched to a
-- provider by (type, workspace). provider_author is the per-provider PR-author
-- allowlist.

CREATE TABLE scm_provider (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(64)  NOT NULL,
    base_url        TEXT         NOT NULL,
    workspace       VARCHAR(255) NOT NULL,
    auth_kind       VARCHAR(16)  NOT NULL,          -- bearer | basic
    auth_username   VARCHAR(255),
    auth_secret     TEXT         NOT NULL,          -- Tink-encrypted token/password
    bot_account_id  VARCHAR(255) NOT NULL DEFAULT '',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (type, workspace)
);

CREATE TABLE provider_author (
    provider_id  UUID         NOT NULL REFERENCES scm_provider(id) ON DELETE CASCADE,
    author       VARCHAR(255) NOT NULL,
    PRIMARY KEY (provider_id, author)
);
