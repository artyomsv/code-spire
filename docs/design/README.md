# Design reference

Visual reference for the Code Spire operator UI. The React `spire-ui` SPA
(list + detail views, live status) is built to match this — treat it as the
approved starting point for the design language, not a throwaway.

## `reviews-ui-mockup.html`

A self-contained, clickable mockup (open it directly in a browser) of the two
operator views:

- **Reviews list** — summary strip, filter chips, and a dense table of PRs. Each
  row encodes state as form (status pill, mini pipeline progress, findings
  count, live-updating time), not just text. Rows are clickable.
- **Per-PR detail** — pipeline stepper with per-stage timings, findings with
  severity stripes, model-usage panel, metadata, and the event stream scoped to
  the single review.

Design language captured here (carry into `spire-ui`):

- **Identity**: observability/terminal lineage. Monospace is the deliberate
  voice for all machine data (reviewIds, commit shas, metrics, stage names,
  findings locations); a clean sans carries UI chrome.
- **Colour**: cool blue-biased neutrals; one brand accent (**iris `#5250e0`**)
  reserved for "active work"; semantic states (`good`/`warn`/`crit`/`muted`)
  kept separate from the accent. Both light and dark themes are first-class.
- **Information design over decoration**: summary before detail; what needs
  attention reads at a glance; live work pulses; reduced-motion respected.

The data shown is illustrative placeholder content (generic repo names, a
"sample data" footer) — it is a layout reference, never wired to a live
orchestrator.
