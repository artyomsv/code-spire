# Code Review State: global / accounts-and-roles

Last reviewed: 2026-09-07
Rounds completed: 1

Branch `feat/accounts-and-roles`, PR #120. Round 1 ran at f5b6bab (four lenses: security-officer, code-reviewer, rules-compliance, qa); the fix wave landed as a8abcd8, 84d4f5f, bd0d998 and was re-reviewed clean. Test tiers at review time: orchestrator 1197, gateway 77, testFast 1043, spire-ui 525 (529 after the fix wave), `tsc` silent, 0 failures.

## Resolved (fixed in code; do not re-raise)
- [rules/H1] `Analytics.tsx` and `ConnectOptions.tsx` sent operators to "Settings → Operators", a nav entry that no longer exists — round 1 (a8abcd8)
- [code-quality/I1] `ServingCell` — an in-flight Verify could land on an account that changed meanwhile; requests are numbered and a stale answer dropped — round 1 (a8abcd8)
- [code-quality/I2] `SettingsProviders.load()` cleared `loading` before the tracker fetch, flashing the empty state when only tracker accounts exist — round 1 (a8abcd8)
- [qa/I1] `fetchServingAccounts` URL construction was never executed by a test; `api.serving.test.ts` pins path, encoding and the error message — round 1 (a8abcd8)
- [security/L1] `/serving` decrypted the factory push token to answer a display question; `MachineAccounts.canAuthenticateAPush(String)` is the shared static predicate, applied to the view — round 1 (84d4f5f)
- [code-quality/S6] `/serving` accepted an unknown forge type and answered a confident "missing"; now 400 — round 1 (84d4f5f)
- [code-quality/S4] `ProviderRole.of` parsed twice in the role guard — round 1 (84d4f5f)
- [qa/m2] the two renamed attention actions (`SCM_PROVIDER_MISSING`, `BOT_IDENTITY_UNRESOLVED`) unasserted — round 1 (84d4f5f)
- [code-quality/S8] `useServingAccounts` reset `lookups` to a fresh `{}` on mount — round 1 (a8abcd8)
- [rules/M2] `.env.example` named the Providers and Webhooks screens — round 1 (bd0d998)
- [rules/M3] four Code Spire own-screen "Webhooks" references in `docs/SMOKE-TEST.md` — round 1 (bd0d998)
- [rules/M5] `docs/ROADMAP.md:141,427` named the Webhooks screen — round 1 (bd0d998)
- [rules/M4] `ProviderRegistry.java` crossed the 300-code-line guideline (294 → 327) without a techdebt row — round 1 (bd0d998)
- [rules/M6] `SettingsWebhookRepos.tsx` (602) and `SettingsContextProviders.tsx` (599) grown past the component cap without an entry — round 1 (bd0d998)
- [code-quality/S9] the Context "Used by" cell is a constant with no owner — techdebt entry — round 1 (bd0d998)
- [rules/L8] code comments naming the old screens (current-screen comments only; the historical note at `App.tsx:42` kept) — round 1 (a8abcd8)
- [rules/M7] `CLAUDE.md` Status snapshot — rewritten by the controller after the final test run (see the closing commit)

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- [code-quality/S3] `resolveIdentity` runs before the role guard on PUT — one wasted `whoami` on a doomed request; hoisting duplicates the stored-role read in the resource (round 1)
- [code-quality/S5] `ProviderRegistry.registration` loads authors nothing reads — a view without authors would lie to a future caller; two cheap queries (round 1)
- [code-quality/S7] `conflict()` helper is now a third copy — a shared helper is its own change (round 1)
- [qa/m3] `AccountsTabs` builds `tab active` by ternary, outside the style contract's literal scan — the classes are defined; the contract test scans literals by design (round 1)
- [rules/L9] the five-state set as bare literals in three places — matches the `"refused"` house shape (round 1)
- [rules/L10] two `stored.get()` after an `isEmpty()` return — house shape (round 1)
- [rules/L11] `ServingAccounts` outside `*Dto`/`*View` naming — informational; rule's paths match nothing here (round 1)
- [rules/L12] one commit body line over 72 chars — cosmetic (round 1)
- [rules/L13] `ProviderFormModal.tsx` holds two components — the split reduced four to two (round 1)
- [re-review/obs] dispatch and display share `canAuthenticateAPush` with no test pinning the coupling — the predicate is the mechanism; `MachineAccountsTest` and `ProviderServingResourceTest` each pin their side on identical rows (round 1)
- [re-review/obs] the forge rows now wait for the tracker fetch to settle — a failure clears the flag; only a hang holds the page; the trade the flash fix asked for (round 1)
