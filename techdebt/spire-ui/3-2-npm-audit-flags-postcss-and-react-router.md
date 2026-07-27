# `npm audit` flags postcss (via vite) and react-router-dom

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-ui/package.json`, `spire-ui/package-lock.json` |
| Found during | Task 11 full verification (attention-panel branch) |
| Date | 2026-07-27 |

## Issue

`npm audit` on `spire-ui` reports 3 vulnerabilities (1 high, 2 moderate), first observed during
the attention-panel branch's final verification pass. Both are newly-disclosed advisories against
already-pinned versions — `package.json`/`package-lock.json` are unmodified on that branch, so
this predates it and was not introduced by it. The CLAUDE.md status history records
`npm audit 0 vulnerabilities` as of the 2026-07 full-project review, so these advisories were
published to the npm registry after that pass.

- **postcss <=8.5.17** (currently 8.5.16, a transitive dev-dependency of `vite@7.3.6`): path
  traversal in sourcemap auto-loading (`GHSA-r28c-9q8g-f849`), high severity. Dev-time only —
  postcss runs during the Vite dev server / build, never in the shipped browser bundle.
- **react-router-dom 6.30.4** (direct runtime dependency, pulls in vulnerable `react-router`):
  open redirect via backslash in `<Link>`/`useNavigate`, and arbitrary constructor injection via
  `deserializeErrors()` in SSR hydration (`GHSA-wrjc-x8rr-h8h6`, `GHSA-337j-9hxr-rhxg`), moderate
  severity. `spire-ui` is a client-rendered SPA (no SSR), so the hydration-deserialization path
  does not apply as shipped; the open-redirect surface is still worth closing.

## Risks

- The postcss advisory only matters if an attacker controls a sourcemap fed to the dev server —
  low risk for a local dev tool, but flagged loudly by `npm audit` on every run, which trains
  operators to ignore its output.
- The react-router open-redirect could be used in a phishing-style redirect if user-controlled
  input ever reaches a `<Link to=...>` / `navigate(...)` call; worth auditing call sites before
  dismissing as SSR-only.

## Suggested Solutions

1. `npm audit fix` resolves the postcss line (patch-level, via the vite dependency tree) with no
   breaking change expected.
2. `npm audit fix --force` upgrades to `react-router-dom@7.18.1` — a major-version bump (v6 to
   v7). Needs its own verification pass (routing API changes) rather than a drive-by fix inside
   an unrelated feature branch.
3. Re-run `npm audit` after any dependency bump elsewhere in `spire-ui` to catch newly-disclosed
   advisories early, since this pair appeared with zero code changes on this branch.
