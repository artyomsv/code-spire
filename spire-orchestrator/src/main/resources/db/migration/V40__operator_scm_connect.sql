-- Self-service proof of which SCM account an operator owns (FR-11).
--
-- operator_identity (V37) is admin-asserted: someone types an OIDC subject, picks an SCM
-- author, and claims they are the same person. That claim cannot be checked. The bot's API
-- token proves only the BOT's identity -- it can confirm a handle exists, but nothing it
-- returns says the human at the browser IS that handle. Matching an OIDC username against an
-- SCM handle has the same flaw with an extra step: on a coincidental match one person sees
-- another person's performance data and nothing on screen looks wrong.
--
-- An SCM sign-in asks the only party that knows. These two tables are what that needs.

-- The OAuth application, one per platform. NOT the provider registry's bot credential: that
-- one answers "who is the reviewer", this one answers "who is this operator".
--
-- Two base URLs because for one platform they genuinely differ (sign-in on github.com, API on
-- api.github.com), and no rule derives one from the other on a self-hosted install. NULL means
-- the platform's own hosted service, which each adapter fills in for itself -- a URL stored
-- here by default would be the core naming a provider, which ADR-020 forbids.
CREATE TABLE scm_oauth_app (
    provider_type  VARCHAR(64)  PRIMARY KEY,
    web_base_url   VARCHAR(512),
    api_base_url   VARCHAR(512),
    client_id      VARCHAR(255) NOT NULL,
    client_secret  TEXT         NOT NULL,          -- Tink-encrypted, AAD-bound to provider_type
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One connect attempt in flight.
--
-- The state value is what stops a third party handing an operator a callback URL that links
-- THEIR SCM account to the operator's dashboard identity. It is stored rather than held in
-- memory for two reasons: a restart between the redirect and the callback would otherwise
-- refuse a legitimate return, and a second replica would never have seen it at all.
--
-- The subject is recorded WITH the state so the callback can check both. The browser's own
-- session already names the operator, so this is not how they are identified -- it is how a
-- callback belonging to somebody else's attempt is refused.
CREATE TABLE oauth_connect_state (
    state          VARCHAR(64)  PRIMARY KEY,
    oidc_subject   VARCHAR(255) NOT NULL,
    provider_type  VARCHAR(64)  NOT NULL,
    redirect_uri   VARCHAR(512) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Expiry is a sweep over this, so it is the only ordering that matters.
CREATE INDEX idx_oauth_connect_state_created ON oauth_connect_state (created_at);
