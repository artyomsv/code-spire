-- Per-repository prompt overrides. scope = '*' for global, else 'workspace/slug'.
--
-- Existing rows take the default and stay global, so nothing changes on upgrade. Resolution is
-- most-specific-wins: repo row, then global row, then the built-in PromptCatalog default.
--
-- A repo row replaces BOTH system and body -- not a per-field merge. Merging would mean an operator
-- editing the global persona silently changed the effective prompt of every repo that had overridden
-- only the body, which is a spooky edit to the instructions a review runs under.
ALTER TABLE prompt_template ADD COLUMN scope TEXT NOT NULL DEFAULT '*';
ALTER TABLE prompt_template DROP CONSTRAINT prompt_template_pkey;
ALTER TABLE prompt_template ADD PRIMARY KEY (scope, kind);
