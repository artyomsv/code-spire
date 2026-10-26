-- The plan cursor of a binding-6 build (M4 slice 3, §6.6, A17), projected from the aggregate when each event is appended:
-- step_index is the next step to build (0-based; step_total once every step completed) and step_total the approved
-- plan's step count. Both are NULL for an item without a plan cursor: a composed item, or one written before the cursor.
-- The aggregate stream is the truth; these columns only let a screen or a query read the cursor without decoding it.
ALTER TABLE work_item ADD COLUMN step_index INT CHECK (step_index IS NULL OR step_index >= 0);
ALTER TABLE work_item ADD COLUMN step_total INT CHECK (step_total IS NULL OR step_total BETWEEN 1 AND 50);
ALTER TABLE work_item ADD CONSTRAINT work_item_step_cursor CHECK ((step_index IS NULL) = (step_total IS NULL)
    AND (step_index IS NULL OR step_index <= step_total));
