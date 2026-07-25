-- One conversation = one thread ref. On SCMs that thread by IMMEDIATE parent (Bitbucket Cloud), a
-- reply to the bot's own answer carries the ANSWER's comment id, not the thread's root, so each turn
-- landed under a different thread_ref: the turn counter never accumulated (the cap could not fire),
-- stored turns split across rows (the review detail showed a bogus "General discussion" card and an
-- under-counted "N replies" label), while the LLM transcript was already root-normalized.
--
-- root_ref links a bot answer's comment id back to its conversation root, so the write side can
-- normalize to the same id the read side uses. NULL = the row IS a root (every pre-existing row,
-- which keeps today's behaviour).
ALTER TABLE review_thread ADD COLUMN root_ref TEXT;
