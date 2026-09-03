-- The push gate's refusal now records WHAT the run did to each blocked path, not only which paths.
--
-- "ci.yml was blocked" does not tell an operator whether the factory edited that workflow or deleted
-- it, and those call for different responses. The publisher has always emitted the kind, and three
-- javadocs argued for carrying it; the worker parsed it out of the JSON and dropped it, so the
-- attention row an operator actually reads could never show it.
--
-- The column is renamed rather than added beside the old one. Two columns holding the same paths is
-- two sources of truth free to disagree, and the name has to change anyway: it no longer holds
-- paths. Postgres rewrites CHECK constraints to follow a renamed column, so the TWO that reference
-- it -- a refusal must name what it refused, and a run cannot both push and be refused -- are
-- carried across untouched and need no surgery here. (Verified against a real Postgres 18: both are
-- rewritten to the new name and both still reject. An earlier version of this comment said "three",
-- having counted the failure_cause CHECK that sits between them in V43 and does not reference this
-- column at all.)
ALTER TABLE factory_run RENAME COLUMN blocked_paths TO blocked_changes;

COMMENT ON COLUMN factory_run.blocked_changes IS
    'JSON array of {"path": ..., "kind": ...}. kind is null when the producer did not say.';

-- Existing rows carry newline-joined paths and no kind. They are converted to the new shape with a
-- NULL kind, which is exactly what is known about them: the run that wrote the row did report a
-- kind, and the code that stored it threw the value away. Inventing one would be worse than
-- admitting it, and a reader cannot tell a guess from a fact after the fact.
--
-- Deliberately NOT left in the old format for the reader to sniff. A dual-format parser is a second
-- code path that only the oldest rows exercise, so it rots unnoticed and fails the day it is needed.
--
-- COALESCE is not decoration. json_agg over ZERO rows returns NULL, and the CHECK carried across by
-- the rename forbids a NULL here for a push_gate_refused row -- so a legacy value that is empty or
-- whitespace-only would abort the migration, Flyway would fail, and the orchestrator would refuse
-- to start until somebody hand-edited the row. Reproduced against a real Postgres before this line
-- was added. No released writer should have produced such a row (PublishRepo.safe rejects a blank
-- path), but a migration is exactly where a hard-to-reach input is worth defending against: it runs
-- once, unattended, and its failure mode is a deployment that will not come up.
UPDATE factory_run
   SET blocked_changes = coalesce((
           SELECT json_agg(json_build_object('path', line, 'kind', NULL) ORDER BY position)::text
             FROM unnest(string_to_array(blocked_changes, E'\n')) WITH ORDINALITY AS t(line, position)
            -- btrim, so a whitespace-only line does not become {"path": "   "} -- a path naming
            -- nothing an operator can act on.
            WHERE btrim(line) <> ''
       ), '[]')
 WHERE blocked_changes IS NOT NULL
   -- Idempotent against a row already in the new shape, so re-running this against a partially
   -- migrated database cannot wrap an array in another array. Shape-based rather than
   -- first-character: "[x]/action.yml" is a legal path, and a legacy row beginning with one would
   -- otherwise be skipped as already-migrated and then fail to parse for ever.
   AND btrim(blocked_changes) !~ '^\[\s*(\{|\])';
