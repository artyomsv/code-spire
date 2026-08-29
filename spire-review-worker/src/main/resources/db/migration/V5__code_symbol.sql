-- Rung 2's structural symbol index (ADR-026 §7.2).
--
-- Structure only, never content: identifiers and the paths they appear in. No hunk text, no file
-- bodies, no snippets — so ADR-011's "diffs are never persisted" needs no carve-out. Unencrypted
-- because the table exists to be QUERIED server-side, matching review_finding/review_thread which
-- store path and line in clear while encrypting the message. Coordinates are queryable; content is
-- encrypted.
--
-- The rows are CANDIDATES, never answers. Nothing is cited from this table: a caller takes paths
-- from it, re-fetches them at the review commit and confirms the reference still exists before
-- citing it. That is what removes staleness structurally -- there is no invalidation pass, and
-- last_seen_commit/last_seen_at are diagnostic and pruning metadata ONLY. No read compares them
-- against the review commit; an implementer writing "if last_seen_commit != review commit" has
-- reintroduced the design this avoids.
CREATE TABLE code_symbol (
    repo             VARCHAR(512) NOT NULL,
    symbol           VARCHAR(255) NOT NULL,
    path             VARCHAR(1024) NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    last_seen_commit VARCHAR(64)  NOT NULL,
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, symbol, path, role)
);

-- callersOf(repo, symbol) is WHERE repo = ? AND symbol = ? AND role = 'REFERENCES'; the primary key
-- already leads with those columns, so it serves the read. This index serves PRUNING instead, which
-- scans by age across every repo and would otherwise be a full scan.
CREATE INDEX idx_code_symbol_last_seen ON code_symbol (last_seen_at);
