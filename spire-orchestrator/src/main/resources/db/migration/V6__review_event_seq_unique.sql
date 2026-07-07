-- The per-review timeline seq was allocated with SELECT MAX(seq)+1 and no
-- uniqueness guard, while three independently-threaded consumers
-- (IntegrationSaga, ResultSaga, DomainEventSink) append to the same review —
-- so duplicate (review_id, seq) pairs are possible and the UI's ORDER BY seq
-- became non-deterministic. Existing dev databases may already hold duplicates:
-- reassign seq from the intended order (seq, tie-broken by insertion id) before
-- enforcing uniqueness. The writer now retries on 23505.

UPDATE review_event re
SET seq = ranked.new_seq
FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY review_id ORDER BY seq, id) AS new_seq
      FROM review_event) ranked
WHERE re.id = ranked.id AND re.seq <> ranked.new_seq;

-- The unique constraint's index supersedes the plain (review_id, seq) index.
DROP INDEX IF EXISTS idx_review_event_review;

ALTER TABLE review_event ADD CONSTRAINT uq_review_event_seq UNIQUE (review_id, seq);
