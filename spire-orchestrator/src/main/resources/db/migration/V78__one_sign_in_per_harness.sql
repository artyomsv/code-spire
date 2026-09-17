-- One sign-in in flight per harness, enforced by the database (M3.5 part F).
--
-- V77 had a non-unique partial index and the check was a read before an insert. Two requests could
-- both see no open row and both publish a Start, which is two containers racing for one seat, two
-- codes in front of one person, and a coin toss over which credential is stored. A read-then-write
-- check cannot exclude that; a unique index can.
DROP INDEX IF EXISTS harness_sign_in_in_progress;

CREATE UNIQUE INDEX harness_sign_in_one_open_per_harness ON harness_sign_in (harness)
    WHERE state IN ('PENDING','PROMPTED');

-- The screen also asks "what is open for this harness" and orders by age; the unique index above
-- serves that lookup, so no second index is created for it.
