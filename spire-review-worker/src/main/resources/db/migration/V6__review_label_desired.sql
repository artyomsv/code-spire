-- The desired verdict label of each review's current run (verdict labels, V120; review waits for CI, orchestrator V121).
-- Written by the orchestrator: a newer run's admission sets its round with no label, the run's verdict or skip sets
-- the label (review: approved, review: changes-requested, review: ci-failed, or NULL for none). A write never moves
-- the round backwards, so a late write of an earlier run changes nothing. The worker never writes it: every label
-- command makes the pull request's bot-owned "review: " label converge to what this row says, then reads it again and
-- converges once more if it changed during the call, so a stale, replayed or redelivered command only re-applies the
-- current run's label.
CREATE TABLE review_label_desired (
    review_id  TEXT        PRIMARY KEY,
    round      INT         NOT NULL,
    label      TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
