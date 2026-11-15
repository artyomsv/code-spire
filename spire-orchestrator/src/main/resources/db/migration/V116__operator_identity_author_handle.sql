-- The SCM login an operator proved by signing in, shown beside the stable id (FR-11).
--
-- A fresh install showed a linked account as its numeric id twice ("3218389 / 3218389"): the only
-- handle the dashboard knew came from authors observed in reviews, and there were none yet. The connect
-- callback has just been told the login by the forge itself, so it is kept with the link.
--
-- Display data only, never a key: author_id stays the identity. NULL for a link an admin made by hand
-- (no login was observed); the dashboard then falls back to the observed authors, then to the id.
-- Never an email address: the callback refuses to store one.
ALTER TABLE operator_identity ADD COLUMN author_handle TEXT;
