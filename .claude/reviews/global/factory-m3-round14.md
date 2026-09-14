# M3 rounds 14 and 15 — restore the shared settings presentation

The replacement repository and work-source screens now use the same table, form,
action and empty-state vocabulary as `SettingsWebhookRepos`. Slice 9 is paused;
this change contains only dashboard presentation, its guards and this evidence.
All seven accepted behavior criteria remain unchanged.

## Review disposition

- Repository registry, detail, pending mappings and Work sources use `prov-table`
  inside `prov-scroll`, shared headings and action styling. Repository and source
  names are text links with `mono nowrap`, retaining their selection handlers.
- Forge origins and webhook paths reuse `wh-url` and `CopyableValue`. The heading
  is **Webhook path**. The existing 180px bound remains unchanged; full values
  remain available for copying and in the title.
- A shared repository account cell reuses `serving-pair` and the existing badge
  vocabulary, with reviewer and factory vertically aligned. Disabled, missing
  and unknown account states remain visibly distinct from configured accounts.
- Pending mappings use a table and concise **Link repository** actions. Target
  origins appear in the row, and registration UUIDs are absent from body copy.
- Work sources, Work items and Approvals have `wh-empty`, `wh-empty-icon`,
  `wh-empty-title` and `wh-empty-text` states with guidance for a fresh install.
- Both Work-source fieldsets retain `disabled={busy}`. Their browser borders are
  removed, text/select labels use `field`, checkboxes use `field-check`, and
  buttons use the shared action styles. Repository forms and the embedded person
  picker use the same control vocabulary.

## Verification

- Full UI: **726 tests, 92 files, zero failures or skips**.
  Report: `.handoff/r14-final-ui.json`.
- Production TypeScript/Vite build: passed. The existing bundle-size advisory
  remains; no dependency or stylesheet change was needed.
- All **40 route tests** passed with shuffled order, seed `5196441335`.
  Report: `.handoff/r14-shuffled-routes.json`. The `.content` route assertion
  and its mutation comment are unchanged.
- **30 production mutation checks**, each with exactly one isolated assertion
  failure and a passing restored case. Originals were restored from byte
  snapshots and their SHA-256 verified. The
  [ledger](factory-m3-round14-mutations.json) records production replacements,
  selected tests, snapshot hashes, final-source hashes and local report paths.
  Final-source hashes also capture the later checkbox/action-row presentation
  refinement, which passed the complete suite and browser checks.

The route-derived guard reads settings routes from `App.tsx`, follows their
rendered local components and checks actual table tags. It asserts that sources,
routes and tables were found before concluding anything. Removing `prov-table`
from each of registry, detail, pending mappings and Work sources killed that
guard independently. It does not treat the imported `webhookPath` helper as a
rendered legacy screen.

Other mutants remove the webhook heading/bound, account stack, each empty-state
class on each of the three screens, a form label and each fieldset's busy lock.
CSS mutations widen `wh-url` or change `serving-pair` direction/alignment. Four
badge mutations distinguish configured, disabled, missing and unknown accounts.
The busy fixtures leave create/edit requests pending and inspect every control,
so an explicit disabled submit button cannot hide an unlocked fieldset.

## Browser and operator stack

Chromium inspection passed against the local Vite helper and again against the
rebuilt dev dashboard at `http://localhost:39285`. Only the UI image/container
was rebuilt; backend services and the run worker were not started or rebuilt.
The container's Work sources and repository-registry source hashes match the
worktree. A full page reload loads the rebuilt dashboard.

Browser API requests were intercepted with TEST fixtures; non-GET requests were
refused. These are presentation measurements, not live API or database proofs.
At 1440px and 900px the four origin/path wrappers measured 180px with ellipsis,
both reviewer/factory pairs stacked vertically, and the document did not overflow
the viewport. Both source fieldsets measured a 0px/none border; their checkbox
measured 16px with its label laid out horizontally. All three empty states had
their visible icon, title and guidance. Updated screenshots were inspected.

Local artifacts:

- `.handoff/r14-repositories-1440.png` and `r14-repositories-900.png`
- `.handoff/r14-sources-populated.png`
- `.handoff/r14-empty-sources.png`, `r14-empty-items.png`, `r14-empty-approvals.png`
- `.handoff/r14-browser-measurements.json`

No database dump, live registration, tracker write or service-tier test was
needed for this presentation correction.
