-- One recorded reference per posted slot, named for what it is.
--
-- V3 added thread_ref beside comment_id so a reconstruction could recover the thread a reply arrives
-- under (GitLab's discussion id differs from its note id). That was the wrong shape: it put the
-- knowledge that SOME providers have two ids into shared code, when nothing outside the adapter needs
-- the comment id at all. The slot records the THREAD; what that id means belongs to the adapter.
--
-- So: comment_id becomes posted_ref (it already held whatever the slot recorded — a comment id for
-- inline findings, "bot"/"human" for resolve markers), and thread_ref goes away again. Keeping a column
-- called comment_id that sometimes holds a discussion id is exactly the mismatch that caused the bug.
ALTER TABLE comment_idempotency RENAME COLUMN comment_id TO posted_ref;
ALTER TABLE comment_idempotency DROP COLUMN thread_ref;
