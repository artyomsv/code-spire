-- One signed-in seat per vendor account (M3.5 part F, review of PR #178).
--
-- V77 left account_ref empty until a real sign-in had been measured. It has been (design section 5.3):
-- the file carries tokens.account_id. Two seats signed in to one account would be two leases on one
-- sign-in, so an enabled seat's account is unique per harness. Signing in to the same account again
-- replaces that seat's file instead of adding a second seat.
--
-- Existing seats have no account_ref yet. The orchestrator fills it at startup from each stored file,
-- switching off all but the newest seat of an account; a seat whose file names no account is never
-- leased and shows "Sign in again".
CREATE UNIQUE INDEX harness_credential_one_seat_per_account
    ON harness_credential (type, account_ref)
    WHERE auth_mode = 'SUBSCRIPTION' AND enabled AND account_ref IS NOT NULL;
