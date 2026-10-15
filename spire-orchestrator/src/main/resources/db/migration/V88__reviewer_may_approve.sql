-- Real GitHub reviews (ADR-047, docs/superpowers/specs/2026-10-15-github-reviews-design.md).
--
-- reviewer_may_approve: the reviewer may post Approve / Request changes on this repository. Off means every
-- review is a Comment and the bot never changes a pull request's merge state (D3, D11). On for every
-- repository that exists today, which is the behaviour the operator asked for.
ALTER TABLE repository ADD COLUMN reviewer_may_approve BOOLEAN NOT NULL DEFAULT TRUE;

-- What the forge holds for the last posted run: the verdict, the forge's review id and the forge's own link
-- to it, so the dashboard can say "Posted as Approved" and link there. The link is stored as the adapter
-- reported it: how a forge addresses a review is provider knowledge the orchestrator never composes.
-- Written under the same commit guard as last_posted_commit.
ALTER TABLE review_status ADD COLUMN posted_verdict TEXT
    CHECK (posted_verdict IN ('APPROVE', 'REQUEST_CHANGES', 'COMMENT'));
ALTER TABLE review_status ADD COLUMN posted_review_ref TEXT;
ALTER TABLE review_status ADD COLUMN posted_review_url TEXT;
