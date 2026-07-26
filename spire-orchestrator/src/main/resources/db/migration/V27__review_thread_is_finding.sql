-- Distinguish a thread the BOT opened for a finding from any other thread that happens to sit on
-- the same line.
--
-- `path`/`line` used to imply "this is a finding's thread", because markFindingThread was the only
-- writer of them. Recording where a HUMAN-started inline thread sits (so the UI can file it at its
-- line instead of under General discussion) broke that implication: two threads can now share one
-- path:line, and the loc -> thread index is deliberately last-wins by seq, so the human's newer
-- thread would win. That index overrides a finding's own threadRef when the prior-run snapshot is
-- built, so reconciliation would have replied STILL_OPEN into a human's thread and tried to resolve
-- it on a closing verdict.
--
-- Backfill is exact rather than a guess: before this migration, every row carrying a path and line
-- was written by markFindingThread.
ALTER TABLE review_thread ADD COLUMN is_finding BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE review_thread SET is_finding = TRUE WHERE path IS NOT NULL AND line IS NOT NULL;
