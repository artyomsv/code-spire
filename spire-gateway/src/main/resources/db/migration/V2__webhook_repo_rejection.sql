-- Why this registration's deliveries are being refused, so a wrong or blanked shared secret
-- surfaces to the operator instead of only reaching a WARN log.
--
-- Deliberately STATE on the row rather than an append-only log: a successfully verified
-- delivery resets the counter, which is what makes the attention panel's row self-clearing.
-- Rotate the secret, the next delivery lands, the row disappears.
--
-- last_rejection_reason is a closed neutral set (provider_mismatch, bad_signature,
-- malformed_payload, out_of_scope) and NEVER an exception message -- a malformed-payload
-- failure can quote payload content.

ALTER TABLE webhook_repo
    ADD COLUMN last_rejected_at      TIMESTAMPTZ,
    ADD COLUMN last_rejection_reason VARCHAR(32),
    ADD COLUMN rejection_count       INTEGER NOT NULL DEFAULT 0;
