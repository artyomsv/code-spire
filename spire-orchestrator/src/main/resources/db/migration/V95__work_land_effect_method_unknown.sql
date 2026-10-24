-- The merge method a land row records is a fact, not the repository's setting (M4 land, review of slice 9).
--
-- The factory now writes the method it sends when it claims the merge (the row turns 'uncertain'), so a lost
-- answer recovered on a later pass records what was sent, even when the setting changed in between. A merge the
-- factory did not make records 'unknown': GitHub's pull request read does not say which method a person used,
-- and the saved setting is no evidence of it.
--
-- Dropped by definition rather than by name, as V42 does: V91 declared the CHECK inline and unnamed.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'work_land_effect'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%squash%';

    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'no CHECK constraint on work_land_effect mentions squash; V91 has changed shape';
    END IF;

    EXECUTE format('ALTER TABLE work_land_effect DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE work_land_effect ADD CONSTRAINT work_land_effect_method
    CHECK (method IS NULL OR method IN ('merge', 'squash', 'rebase', 'unknown'));
