-- Per-instance Jira project keys (e.g. 'ACME') used to narrow candidate issue keys parsed from a
-- PR's title/branch/description. Space/comma-separated; NULL/blank = accept every well-formed key (the
-- generic behavior). Not a secret — stored in cleartext.
ALTER TABLE context_provider ADD COLUMN project_keys TEXT;
