-- How hard the model thinks, chosen with the model (M3.5 part M).
--
-- Codex offers a thinking level per model, and the levels differ by model: measured 2026-09-18, one
-- allows low..ultra and another only low..xhigh. So it is stored beside the model it belongs to, and it
-- is checked against THAT model's own list when the harness's catalogue is known.
--
-- NULL means "the model's own default", which is a real choice and not a missing value: the vendor
-- publishes a default per model, and an operator who has not picked one gets that one.
ALTER TABLE repository_build_defaults ADD COLUMN effort TEXT;
