-- How a run paid, on the run itself (M3.5 part F, design section 5.7).
--
-- Charging used to read it through harness_credential_id, but a re-armed dispatch NULLS that column on
-- purpose (FactoryRunProjection.queued explains why), so a subscription retry was priced as if an API
-- key had paid. How a run pays is fixed by its approval and does not change on a retry, so it lives
-- here, where a re-arm cannot erase it. Every existing run paid with an API key.
ALTER TABLE factory_run ADD COLUMN paid_by VARCHAR(16) NOT NULL DEFAULT 'API_KEY';
ALTER TABLE factory_run ADD CONSTRAINT factory_run_paid_by_known CHECK (paid_by IN ('API_KEY', 'SUBSCRIPTION'));
