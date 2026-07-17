-- Which SCM thread a conversation turn (AuthorReplied / FollowUpGenerated) belongs to.
-- NULL for non-conversation events and for turns recorded before this column existed.
ALTER TABLE review_event ADD COLUMN thread_ref TEXT;
