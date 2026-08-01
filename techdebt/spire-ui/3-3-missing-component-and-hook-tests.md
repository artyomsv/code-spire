# The two largest forms are covered only through their extracted helpers

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-ui/src/components/SettingsProviders.tsx` (585 lines), `spire-ui/src/components/RegisterPrDialog.tsx`, `spire-ui/src/App.tsx` |
| Found during | Full-project QA review (4-agent) |
| Date | 2026-07-07, corrected 2026-08-02 |

## Issue

**Correction (2026-08-02).** This entry originally read "no component-test infra
(testing-library/jsdom) is set up" and "zero tests for the one hook". Both are now out of date:

- The infra exists. `@testing-library/react`, `@testing-library/jest-dom` and `jsdom` are
  devDependencies, `vite.config.ts` sets `environment: 'jsdom'` with a `vitest.setup.ts` that wires
  the DOM matchers and explicit cleanup, and roughly a dozen component tests render through it
  (`AttentionBell`, `ContextCard`, `EventStream`, `Select`, `SettingsGeneral`, the two settings
  forms' field logic, `ReviewDetail.layout`, and more).
- **`useLiveReviews` is now covered** — the entry's own first priority. Ten tests over the WS/REST
  merge: snapshot ordering, the `wsDelivered` guard on both the resolve and the reject path, array
  vs object frames, upsert-in-place, removal frames, the string-`id` drop, a non-JSON frame, and
  reconnect/unmount timer cleanup. Each guard was verified by mutation to fail its own test alone.

What remains is the part the original entry rated highest by size and never got to.

`SettingsProviders.tsx` is 585 lines — the provider-registration form, which decides what reaches
the provider CRUD API — and its only test imports one exported helper (`conversationLabel`).
`RegisterPrDialog.tsx` is the same shape: `parsePrNumber` is tested, the form around it is not.
`App.tsx` (routing, layout, the settings shell) has no test at all.

## Risks

Form validation is what stands between an operator's typo and a bad row in the provider registry —
a wrong base URL or auth kind fails later, during a real review, as an SCM error the operator has to
trace back. The extracted-helper tests prove the parsing rules; nothing proves the form applies
them, keeps the submit button disabled, or surfaces the failure.

Lower than when filed: the live-data merge logic that regressed silently is now pinned, and the
`ProviderLastChecked` / attention-panel work added rendering coverage around the same screens.

## Suggested Solutions

1. **Component-level tests for the two forms**, driving them through `@testing-library/react` the way
   `SettingsWebhookRepos.form.test.tsx` and `SettingsContextProviders.form.test.tsx` already do —
   those are the working pattern to copy, and they exist because the same gap was closed for the
   newer settings screens.
2. Keep extracting pure helpers out of components (the established pattern), so the highest-value
   logic stays testable without rendering.
3. `App.tsx` is mostly composition; a smoke test that each route renders its screen is worth more
   than exhaustive coverage of it.
