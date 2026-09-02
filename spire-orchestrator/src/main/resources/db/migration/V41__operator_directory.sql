-- Who has signed in, so an operator can be PICKED rather than typed.
--
-- The Operators screen asked an admin to type an OIDC subject: an opaque id the product
-- displays nowhere, so the only way to fill the field was to query the database. Every one of
-- those subjects arrives on a real request; nothing was recording them.
--
-- Deliberately not a user directory. It holds who has actually signed in, which is the set an
-- admin can meaningfully link -- not everyone the identity provider knows about, which this
-- deployment has no right to enumerate and no way to keep current. A row appears the first
-- time someone signs in and never grants anything: authorization is the token's roles, always.
--
-- No email column, per the project rule that email is never logged or persisted.
CREATE TABLE operator_seen (
    oidc_subject   VARCHAR(255) PRIMARY KEY,
    username       VARCHAR(255) NOT NULL DEFAULT '',
    display_name   VARCHAR(255) NOT NULL DEFAULT '',
    first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The picker orders by who was here most recently.
CREATE INDEX idx_operator_seen_last ON operator_seen (last_seen_at DESC);
