-- Repository ownership replaced account workspace in V61. The populated column was retained
-- through the M3 review slices; the final upgrade requires a validated pre-migration backup.
ALTER TABLE scm_provider DROP COLUMN workspace;
