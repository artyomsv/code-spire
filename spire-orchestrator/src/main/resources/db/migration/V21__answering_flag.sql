-- Transient "the bot is answering a reply" hint for the dashboard (fix #5). Set when a
-- follow-up is dispatched, cleared when it posts or terminally fails. Best-effort UI signal.
ALTER TABLE review_status ADD COLUMN answering BOOLEAN NOT NULL DEFAULT FALSE;
