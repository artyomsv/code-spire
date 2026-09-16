-- Paying for a Codex run with a subscription instead of tokens (M3.5 part F, ADR-031).
--
-- Two changes: the pool learns that a member can be a SIGN-IN rather than a key, and a sign-in in
-- progress gets somewhere to live while a person walks to their phone.

-- How this member pays. Existing rows are keys, because until this migration there was nothing else
-- a member could be.
--
-- Stored as a name rather than a boolean: a third way to pay is not hard to imagine (a device token,
-- an enterprise seat), and a boolean called is_subscription would have to be rewritten to admit one.
ALTER TABLE harness_credential ADD COLUMN auth_mode VARCHAR(32) NOT NULL DEFAULT 'API_KEY';
ALTER TABLE harness_credential ADD CONSTRAINT harness_credential_known_auth_mode
    CHECK (auth_mode IN ('API_KEY', 'SUBSCRIPTION'));

-- api_key is NOT renamed, and that is a decision rather than an oversight.
--
-- The design said "rename api_key to secret". Renaming a column that every existing row depends on,
-- in the same migration that adds a second kind of value to it, means one statement carrying two
-- risks: the rename can break a reader nobody remembered, and the new kind can be wrong. They are
-- separated. What the column holds is now described here, and a rename can follow once a real
-- subscription has been stored and read back.
COMMENT ON COLUMN harness_credential.api_key IS
    'Tink-encrypted, AAD bound to the row id. For auth_mode=API_KEY this is the vendor key. For '
    'auth_mode=SUBSCRIPTION it is the whole sign-in file the vendor CLI wrote, opaque to this '
    'system: only auth_mode inside it has ever been measured. The column keeps its name until a '
    'rename can be made on its own.';

-- Which account the sign-in belongs to, as the vendor reports it.
--
-- NULL for every key, and NULL for a subscription too until a real sign-in has been measured: the
-- field that carries an account identity inside that file has not been seen by this build, and a
-- column filled from a guessed field name is worse than an empty one. No UNIQUE constraint yet for
-- the same reason -- the design asks for one so that a seat cannot be signed in twice and then
-- leased twice, and it can be added truthfully once there is a value to put in it.
--
-- It will never hold an e-mail address. Identity is a stable id here as everywhere else.
ALTER TABLE harness_credential ADD COLUMN account_ref TEXT;

-- A sign-in a person is in the middle of.
--
-- Its own table rather than a half-filled harness_credential row: a pending sign-in has no secret,
-- cannot be selected, and must never be visible to the pool's rotation query. A row here becomes a
-- pool member only when the vendor has actually answered.
CREATE TABLE harness_sign_in (
    id           UUID         PRIMARY KEY,
    -- What the operator typed before pressing start. Carried through so the member it becomes is
    -- named by a person rather than by a generated id.
    label        VARCHAR(128) NOT NULL,
    harness      VARCHAR(64)  NOT NULL,

    -- PENDING  -- the unit is starting; nothing to show yet
    -- PROMPTED -- the link and code are known and on screen
    -- COMPLETE -- a member was created from it
    -- FAILED   -- it ended without one, and reason says why
    state        VARCHAR(16)  NOT NULL,
    CONSTRAINT harness_sign_in_known_state CHECK (state IN ('PENDING','PROMPTED','COMPLETE','FAILED')),

    -- What the operator must do. Not secret: the code authorises nothing on its own -- only the
    -- account holder can approve it, and only the unit that started the flow can collect the result.
    -- It is still short-lived and single-use, which is why expires_at sits beside it.
    verification_uri TEXT,
    user_code        VARCHAR(64),
    expires_at       TIMESTAMPTZ,

    -- Why it failed, as a reason a screen maps to a sentence. Never a vendor message.
    reason       VARCHAR(64),

    -- The member it became, so a screen can link the two and so a second completion for the same
    -- sign-in cannot create a second member.
    credential_id UUID REFERENCES harness_credential (id),

    -- Who started it, as the OIDC subject. Not an e-mail address.
    started_by   TEXT         NOT NULL CHECK (btrim(started_by) <> ''),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The screen asks one question: is anything in progress for this harness? A partial index, because
-- a finished sign-in is history and indexing it only slows the writes that finish one.
CREATE INDEX harness_sign_in_in_progress ON harness_sign_in (harness, created_at DESC)
    WHERE state IN ('PENDING','PROMPTED');
