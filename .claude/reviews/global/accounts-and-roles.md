# Code Review State: global / accounts-and-roles

Last reviewed: 2026-09-08
Rounds completed: 2

Branch `feat/accounts-and-roles`, PR #120. Round 1 ran at f5b6bab (four lenses: security-officer, code-reviewer, rules-compliance, qa); the fix wave landed as a8abcd8, 84d4f5f, bd0d998 and was re-reviewed clean. Test tiers at review time: orchestrator 1197, gateway 77, testFast 1043, spire-ui 525 (529 after the fix wave), `tsc` silent, 0 failures.

Round 2 ran at d7d8f2e over `e9cff10..HEAD` — the nine commits of the operator's walkthrough fix
wave (UI only; no Java file changed in the range). Four lenses again. No HIGH from any of them;
Semgrep scanned 16 files with 0 findings. The fix wave landed as the commit that follows this
entry. Test tiers after it: `spire-ui` 71 files / 560 tests / 0 failures, `tsc` silent.

## Resolved (fixed in code; do not re-raise)
- [code-quality/I1 round 2] `EnabledDot` named a bare `<span>` — the ARIA `generic` role cannot be
  named, so the dot had no accessible name in a browser and Enabled was carried by colour alone;
  `role="img"` added. jsdom does not implement the prohibition, so NO test can hold this — measured,
  and written into the component's comment instead — round 2
- [code-quality/I2 round 2] the connection button was named after its state ("OK, button"), never
  its action; the accessible name now says "OK — check the connection" — round 2
- [code-quality/I3 round 2] a DISABLED account whose stored check was a rejection rendered as the
  grey "Not checked" icon, with the rejection surviving only in a tooltip whose own head contradicted
  it. `storedState()` is now shared by both badges, so a disabled row reports what the registry
  stored — round 2
- [code-quality/S9 round 2] the other half of the same defect: a live "ok" outlived the account that
  earned it, so disabling a green account left the green behind. `load()` drops results for accounts
  that are no longer enabled — round 2
- [code-quality/I4 round 2] `.wh-url`'s bound was on a `<td>`, where `max-width` is undefined in
  CSS 2.1 and ignored by every browser under `table-layout: auto` — the payload path never ellipsed.
  Moved to a wrapping div, the shape `.cell-cap` already used — round 2
- [code-quality/I5 round 2] `shortConversation` compared the label the FORM renders, so a reworded
  label widened the cell back and an unknown level read as "Inherit" — the `refused` shape. It
  switches on the level and names an unknown one, like `roleLabel` beside it — round 2
- [code-quality/I6, rules/1 round 2] `AccountsTable.tsx` reached 345 lines against a 250 cap, and
  the techdebt entry for two other screens cited its own 177 as the precedent to copy. Split: the
  table (166) and `AccountsCells.tsx` (239), no techdebt entry needed — round 2
- [qa/gap-6 round 2] `styles.contract.test.ts` read only quoted `className` attributes and excused
  whole families by prefix, so `conn-badge`, `enabled-dot` and `prov-scroll` could be deleted from
  the stylesheet with the suite green. It now reads the static words of a template literal and drops
  the composed ones, and the prefix list is gone — which immediately caught a real orphan,
  `prov-error`, asked for twice by the General screen and defined nowhere — round 2
- [qa/gaps 1-5 round 2] five behaviours no test reached: the "Inherit" mapping and an unknown level
  (new `AccountsCells.test.tsx`), a tracker whose check passed, a disabled account's Disabled dot,
  the suppression of the stored date on a freshly-answered row, and the VISIBLE id count, whose
  assertion had moved onto a tooltip alone — round 2
- [security/L1, code-quality/S2 round 2] `takeReturnRoute` admitted `#//host` and `#/\host`; the
  guard refuses them itself rather than relying on HashRouter making them inert — round 2
- [security/L2 round 2] comments claimed the tooltip carried "the provider's own words". The server
  sends one of a fixed set of strings keyed on the HTTP status; the comment now says so, because a
  later change that "restored" the provider's body would be a way to put a token on a hover — round 2
- [security/L3 round 2] `rememberReturnRoute` touched `sessionStorage` between the in-flight flag and
  the navigation it guards, so a browser blocking site data wedged the tab on "Signing in…" — round 2
- [code-quality/S3 round 2] the identity copied the `@` a forge does not want; shown with it, copied
  without — round 2
- [code-quality/S4/S5/S6 round 2] the serving lookup computed twice per row; "Not checked · Never
  checked" said the same thing twice; three per-row style objects and one per-cell one hoisted — round 2
- [code-quality/S7 round 2] the em-dash assertions counted dashes across a whole row, where an empty
  `CopyableValue` renders one too; scoped to the Policy cell, with the column count asserted — round 2
- [rules/2, rules/3 round 2] the techdebt entry's two stale measurements and CLAUDE.md's test count — round 2
- [rules/4 round 2] the spec's §13 acceptance line still required the per-chip Verify; marked
  superseded by §14 — round 2
- [rules/5 round 2] the two `policy-bit` spans had no accessible name — round 2
- [rules/6 round 2] `App.routes.test.tsx` had no trailing newline — round 2
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
- [code-quality/S1 round 2] one chained login marks every lapsed prefix attempted, so a chain that
  half-completes for a reason other than the prefix being unfixable retires one that was never
  really tried, for the life of the tab. Bounded and recoverable: `apiFetch` re-logs a prefix it
  gets a 401 from without consulting the marks, and a reload clears them. The guard exists to stop a
  login loop, and loosening it to two attempts trades a rare stuck socket for a second full-page
  navigation in the case the operator complained about. Revisit with its own change (round 2)
- [rules/7 round 2] commit `fbe6591` has an empty body — it is already pushed, and rewriting a
  pushed commit for a one-line body is not worth it (round 2)
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
