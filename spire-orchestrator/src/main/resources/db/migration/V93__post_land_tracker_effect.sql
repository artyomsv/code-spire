-- The ticket after land (M4 land, spec section 7.7, amendment 1).
--
-- A 'phase' row is bound to the phase that enqueued it: it is sent only while the item is still at that
-- revision and phase, under the current policy mode. A 'post_land' row is enqueued in the same transaction
-- as WORK_ITEM_LANDED and is drained whatever the item's phase or status afterwards (the item is complete by
-- then): only the generation must match, and a close is sent only while the repository's ticket rule is
-- still 'close', after the comment was sent. Invalidating milestones refuse only 'phase' rows.
ALTER TABLE work_tracker_outbox ADD COLUMN effect_class TEXT NOT NULL DEFAULT 'phase'
    CHECK (effect_class IN ('phase', 'post_land'));

-- What the ticket rule could not do after a landing, shown to the operator as a note: a Jira ticket under
-- 'close' gets the comment and keeps its status (ticket_close_unsupported). Kept apart from note, which
-- says whether the branch delete gave up.
ALTER TABLE work_land_effect ADD COLUMN ticket_note TEXT;
