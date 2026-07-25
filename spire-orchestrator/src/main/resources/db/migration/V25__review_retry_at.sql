-- When the next attempt at a retryable review failure is due.
--
-- The review pipeline used to re-dispatch immediately: three attempts could burn inside ten seconds and
-- land entirely inside the same provider outage (observed against an LLM returning 500s). The obvious
-- fix — sleeping between attempts, as the follow-up worker does — is not available here: ResultSaga's
-- consumer is @Blocking and ordered per partition, so sleeping would stall every other review sharing
-- that partition.
--
-- So the wait is persisted instead of held on a thread. A scheduled sweeper claims rows whose retry_at
-- has passed and dispatches them. Two properties fall out of that: an orchestrator restart mid-backoff
-- resumes the retry rather than losing it, and the claim is a single atomic UPDATE, so replicas cannot
-- both dispatch the same attempt.
--
-- NULL = nothing scheduled (the normal state).
ALTER TABLE review_status ADD COLUMN retry_at TIMESTAMPTZ;

-- The sweeper polls "anything due?" frequently; a partial index keeps that off the full table.
CREATE INDEX review_status_retry_due ON review_status (retry_at) WHERE retry_at IS NOT NULL;
