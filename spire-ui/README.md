# spire-ui

The operator UI for Code Spire — a React + Vite + TypeScript SPA with two views:

- **Reviews list** (`/`) — every review with live status, filterable.
- **Per-PR detail** (`/r/:workspace/:slug/:pr`) — pipeline stepper, findings,
  model usage, and the review's scoped event stream.

It is a thin read-side client: it calls `GET /api/reviews` and
`GET /api/reviews/{workspace}/{slug}/{pr}` on the orchestrator and subscribes to
`/ws/reviews` for live updates. It never talks to the domain directly.

## Develop

The backend (orchestrator on `:34080`) must be running first — see the repo
README. Then:

```bash
cd spire-ui
npm install
npm run dev      # Vite dev server on http://localhost:34000
```

Open **http://localhost:34000**. The dev server proxies `/api` and `/ws` to the
orchestrator on `:34080`, so it is single-origin — no CORS, no extra config.
The list is empty until a review is registered (open a PR against a repo whose
webhook points at the gateway; in observe mode it appears without being acted on).

## Build

```bash
npm run build    # tsc + vite build -> dist/
```

The design language matches `docs/design/reviews-ui-mockup.html`; `src/index.css`
is that mockup's stylesheet.
