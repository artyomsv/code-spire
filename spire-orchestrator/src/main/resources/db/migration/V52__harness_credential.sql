-- The harness credential pool (FR-F12, ADR-031).
--
-- A separate table from llm_provider, and separate is the decision. They hold the same KIND of
-- secret and are used by opposite trust levels: llm_provider's key is called by the reviewer, which
-- is our own code sending a fenced prompt, while this key is handed to an agent that runs a model on
-- an untrusted work item at full shell access. A prompt-injected agent can read its own environment.
--
-- Until now a run took the deployment's DEFAULT llm_provider key when none was named, so one
-- exfiltration disabled reviews and runs together, and a spend spike from a leaked key was
-- indistinguishable in the ledger from legitimate factory use. That is the same argument
-- scm_provider.role already settled for the push identity (ADR-038), reached again from the model
-- side -- and settled the same way, by refusing to share rather than by sharing carefully.
--
-- A pool rather than one row, because the factory's failure mode is exhaustion. A reviewer makes one
-- call and reports a failure to a person; an agent runs for an hour and can exhaust a key's quota
-- mid-run, and the next run must not simply fail behind it.
--
-- The two exhaustion states are NOT one column with a timestamp, and that is the point of the shape.
-- A rate limit is a promise: capacity returns at a stated time, and the pool heals itself. A
-- rejection is an answer: the key is wrong, revoked or out of credit, and retrying it spends one
-- request per run to learn nothing. Collapsing them means either retrying a dead key for ever or
-- treating a five-minute pause as a permanent fault -- and the first is how a pool quietly stops
-- rotating while looking healthy.

CREATE TABLE harness_credential (
    id            UUID         PRIMARY KEY,
    -- The operator's own name for it, so a rejection names something they can find in a vendor
    -- console. Unique, because "which key is dead" is unanswerable when two are called the same.
    label         VARCHAR(128) NOT NULL UNIQUE,
    type          VARCHAR(64)  NOT NULL,   -- openai | anthropic | gemini, as llm_provider
    base_url      TEXT         NOT NULL,
    api_key       TEXT         NOT NULL,   -- Tink-encrypted, AAD bound to the row id
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Rotation state. All three are NULL for a member that has never been used or has never failed.
    last_used_at  TIMESTAMPTZ,

    -- When a rate limit lifts. The vendor usually says; when it does not, the application supplies a
    -- bounded default rather than leaving the member out for ever.
    rate_limited_until TIMESTAMPTZ,

    -- When the provider refused the key. No expiry column beside it, deliberately: nothing but an
    -- operator clears this, because a key that was refused will be refused again and a pool that
    -- retries it burns a run to rediscover that.
    rejected_at   TIMESTAMPTZ,

    -- The last time this member was rate-limited or rejected, kept even after recovery. It is the
    -- rotation ORDER: prefer the member rested longest, so a pool under load spreads rather than
    -- hammering whichever one answered most recently.
    exhausted_at  TIMESTAMPTZ,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A member cannot be both, and the pairing is what keeps the two recovery rules apart. A row
    -- claiming a rate limit AND a rejection has no defined return time, so the selector would have
    -- to guess -- and the guess that reads it as a rate limit retries a dead key for ever.
    CONSTRAINT harness_credential_one_exhaustion_state
        CHECK (rate_limited_until IS NULL OR rejected_at IS NULL)
);

-- The selector's own query: enabled members that are not rejected and not still rate-limited,
-- ordered by how long they have rested. Partial, because a rejected member is never selected and
-- indexing it only slows the writes that mark one.
CREATE INDEX harness_credential_rotation ON harness_credential (exhausted_at NULLS FIRST, last_used_at NULLS FIRST)
    WHERE enabled AND rejected_at IS NULL;

-- Which pool member a run was dispatched with.
--
-- On the run's own row rather than carried back on the result, because it is the ORCHESTRATOR's
-- fact: it chose the member at dispatch. A worker echoing it back would let the two disagree with
-- nothing to say which is right, which is the same reasoning factory_run.model already rests on.
--
-- Nullable, and it stays nullable: every run dispatched before this migration has no pool member,
-- and inventing one would put a guess in the column that decides which key gets marked dead.
ALTER TABLE factory_run ADD COLUMN harness_credential_id UUID REFERENCES harness_credential (id);

-- A run's charges, attributable to the key that paid for them.
--
-- V42 added llm_charge.credential_ref for exactly this and nothing has ever written it, because
-- until this migration there was no per-run credential identity to write. On an UNMETERED
-- deployment every run's charge is an asserted zero, so "which key spent this" was unanswerable by
-- any other route.
COMMENT ON COLUMN llm_charge.credential_ref IS
    'The harness_credential.id a factory run was dispatched with, or NULL for a review call and for '
    'any run dispatched before V52.';
