-- A refused run raises an attention row naming the paths the gate blocked (ROADMAP M0 exit
-- criterion 2). The panel's contract is that fixing the cause removes the row, and a refusal is
-- history that nothing un-refuses — so, exactly like review_status.attention_ack_at, the row clears
-- on an operator's acknowledgement rather than never.
ALTER TABLE factory_run ADD COLUMN attention_ack_at TIMESTAMPTZ;
