-- The built-in default AS IT STOOD when the operator saved this override -- the common ancestor a
-- customization forked from.
--
-- Without it, an improvement to the shipped prompt and the operator's own edits are
-- indistinguishable, which is why the only answer used to be reset-to-default (discarding the
-- customization wholesale). NULL means the row predates this column: the ancestor is UNKNOWN, which
-- is not the same as "matches the current default" and must not be reported as such.
ALTER TABLE prompt_template ADD COLUMN base_system_text TEXT;
ALTER TABLE prompt_template ADD COLUMN base_body_text   TEXT;
