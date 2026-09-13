-- Only coordinates survive an interrupted page. Ticket content remains on the tracker.
ALTER TABLE work_source ADD COLUMN scan_batch UUID;
ALTER TABLE work_source ADD COLUMN scan_next_cursor TEXT;
CREATE TABLE work_scan_candidate (
    source_id UUID NOT NULL REFERENCES work_source(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
    issue_key TEXT NOT NULL CHECK (issue_key <> ''),
    tracker_url TEXT NOT NULL CHECK (tracker_url <> ''),
    PRIMARY KEY(source_id, position)
);
