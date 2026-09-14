-- Current condition, maintained atomically with the explicit policy-clamp milestone.
ALTER TABLE work_item ADD COLUMN policy_clamped BOOLEAN NOT NULL DEFAULT false;
