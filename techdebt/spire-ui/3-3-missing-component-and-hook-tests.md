# UI components and hooks have no tests beyond pure render helpers

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-ui/src/useLiveReviews.ts`, `spire-ui/src/components/SettingsProviders.tsx`, `spire-ui/src/components/RegisterPrDialog.tsx`, `spire-ui/src/components/ReviewDetail.tsx`, `spire-ui/src/components/ReviewsList.tsx`, `spire-ui/src/App.tsx`, `spire-ui/src/api.ts` |
| Found during | Full-project QA review (4-agent) |
| Date | 2026-07-07 |

## Issue

Vitest only covers pure functions (render helpers, and the helpers added during the 2026-07-07
review fixes). There are zero tests for the one hook (`useLiveReviews` — WS/REST merge logic,
snapshot ordering) and all components, including `SettingsProviders.tsx` (~464 lines, the
provider-registration form) and the `ReviewDetail` stale-fetch/live-update interplay. No
component-test infra (testing-library/jsdom) is set up.

## Risks

The WS-vs-REST merge and stale-response guards are exactly the kind of logic that regresses
silently; form validation in SettingsProviders guards what gets sent to the provider CRUD API.

## Suggested Solutions

1. Add `@testing-library/react` + jsdom to the vitest setup; start with `useLiveReviews`
   (mock WebSocket + fetch, assert snapshot-ordering rules) and `RegisterPrDialog` validation.
2. Keep extracting pure helpers out of components (current pattern) so the highest-value logic
   stays testable without heavy infra.
