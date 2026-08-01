# `react-router-dom` needs a v6→v7 major bump to clear the open-redirect advisory

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-ui/package.json`, `spire-ui/package-lock.json` |
| Found during | Wave 1/2 debt pass — successor to `3-2-npm-audit-flags-postcss-and-react-router` |
| Date | 2026-08-01 |

## Issue

The predecessor entry covered two advisories. **The postcss one is closed** — `npm audit fix` took the
transitive dev-dependency past `8.5.17`, clearing the only *high* finding, with `tsc --noEmit` clean
and 181 vitest tests green afterwards.

What remains is `react-router-dom` 6.30.4, held back by `react-router 6.0.0 - 7.17.0`:

- **Open redirect via backslash in `<Link>` / `useNavigate`** (`GHSA-wrjc-x8rr-h8h6`, a CVE-2025-68470
  bypass), moderate.
- **Arbitrary constructor injection via `deserializeErrors()` in SSR hydration**
  (`GHSA-337j-9hxr-rhxg`), moderate.

Only `npm audit fix --force` clears them, and it installs `react-router-dom@7.18.2` — a major bump
whose routing API changes need their own verification pass.

## Risks

**Neither advisory is reachable in `spire-ui` as it currently ships**, which is why this is deferred
rather than urgent:

- The SSR hydration path does not exist here — `spire-ui` is a client-rendered SPA with no server
  rendering, so `deserializeErrors()` is never called.
- The open redirect needs a `to` value that a browser reads as protocol-relative (`\\host`, `/\host`).
  Every navigation target in the app was audited: `App.tsx:224`, `ReviewDetail.tsx:74/85/98/207` and
  `PromptDetail.tsx:50` are literals; `ReviewsList.tsx:85` and `PromptsSettings.tsx:55` interpolate
  server data after a literal `/r/` or `/settings/prompts/` prefix; `AttentionBell.tsx:100` follows
  `item.action`, which `AttentionQueries` builds as `"/r/" + workspace + "/" + slug + "/" + pr`. In
  every case the interpolated value lands in the *third* path segment or later, so it cannot form the
  leading `//` the bypass requires — and none of the three SCMs permit a backslash in a workspace or
  repository name in the first place.

The real cost is the noise: `npm audit` reports non-zero on every run, which trains an operator to
stop reading its output — and that is how the next advisory, on a path that *is* reachable, gets
missed.

## Suggested Solutions

1. **Do the v7 bump as its own change**, with a verification pass over routing: v7 moves several
   APIs and changes data-router defaults. `spire-ui`'s router usage is small (7 call sites, listed
   above), so the migration should be contained — the cost is verification, not rewriting.
2. Re-run `npm audit` after any `spire-ui` dependency change. This pair appeared with zero code
   changes on the attention-panel branch, so a clean audit ages on its own.
