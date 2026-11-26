-- The replies the bot is answering (review of PR #457, code-quality/M-1).
--
-- review_status.answering is one flag per review, but replies are answered one comment at a time and several can be
-- outstanding: reply A is being answered when reply B arrives and is declined. One row per outstanding reply; the flag
-- stays the read model the dashboard reads, true while any row is left. AnsweringMarks keeps both in one transaction,
-- under the review_status row lock: an ask adds its row and sets the flag, a result removes its own row and sets the
-- flag to whether any row is left.
--
-- A new run, an archive, a closed pull request and a posted answer from before this table clear the flag without
-- naming a comment. The trigger below drops the review's rows in that same statement, so a row left from before the
-- clear can never raise the mark again when another reply finishes.
CREATE TABLE review_answering (
    review_id  VARCHAR(512) NOT NULL REFERENCES review_status (review_id) ON DELETE CASCADE,
    comment_id TEXT         NOT NULL,
    started_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, comment_id)
);

CREATE FUNCTION review_answering_cleared() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM review_answering WHERE review_id = NEW.review_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER review_answering_cleared AFTER UPDATE ON review_status FOR EACH ROW
    WHEN (OLD.answering AND NOT NEW.answering) EXECUTE FUNCTION review_answering_cleared();
