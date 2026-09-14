# Factory M3 round 8 — route smoke-test isolation

Round 8 independently accepted criterion 3. Criteria 3, 5, 6 and 7 are verified.

## Diagnosis

Dashboard run 34770126825, job 103758077066, reports an uncaught exception at
`WorkItemDetail.tsx:33`: `Object.entries(item.effectiveModes)` receives undefined.
The detail fixture on `dea12d3e` omits the required mode maps and applied-label list.
The API supplies those fields; the production component needs no fallback.

The smoke test waits for the shell's `Loading…`, whereas the page renders
`Loading work item…`. Its initial wrapper can satisfy `.content` before the detail
response renders. That explains why an isolated pass is insufficient evidence of
mock pollution: it can finish before exercising the malformed fixture.

A controlled local reintroduction of the missing fields failed both the isolated
detail case (1 failure, 34 skipped) and the complete file (1 failure, 34 passed),
each with the same uncaught exception. Correcting only the fixture passed 35/35
in normal order and with shuffle seeds 42, 99 and 5191448392, before changing
the mock lifecycle. This isolates the demonstrated CI cause from the teardown hazard.

## Correction and proof

The fixture now satisfies the actual `WorkItemDetail` TypeScript contract. The
route case waits for both the workflow and tracker headings before making the
unchanged `main .content` assertion. No assertion or production component was loosened.

Every case now gets fresh session, storage, fetch and socket state, including the
rail-highlighting describe that previously had no local setup. Cleanup unmounts
components before restoring globals, closing the existing boundary where passive
effects could reach native globals before the setup-file cleanup. Global mock
restoration remains in `vitest.setup.ts`.

The final complete file passes 35/35 in normal order and 35/35 at each of the three
shuffle seeds above. Removing the production detail wrapper's `content` class fails
exactly the retained `.content` assertion, with both response-heading assertions
already passed and no uncaught error. The production file was restored from scratch
bytes, SHA-256 `8B82E0D72CB78EE37ADDBEDFBDA56AD95B603BE160F9AACB4ED1ADA9FC4EBBE4`;
the restored file passed all three shuffled runs. This is an additional wrapper
mutation proof, separate from the original slice 5 inventory of 154 checks.

Reproduce from `spire-ui`:

```sh
npm test -- --run src/App.routes.test.tsx
npm test -- --run src/App.routes.test.tsx --sequence.shuffle --sequence.seed=42
npm test -- --run src/App.routes.test.tsx --sequence.shuffle --sequence.seed=99
npm test -- --run src/App.routes.test.tsx --sequence.shuffle --sequence.seed=5191448392
npm test -- --run --maxWorkers=4
npm run build
```

Raw diagnostics, mutation output and final results are in `.handoff/r8-*.log`.
The earlier full 650-test run preceded the last policy-display additions; only
12 targeted tests and the build followed those additions. That verification gap
let the stale route fixture reach CI and is corrected by the full-suite rerun.

Final verification: **650/650 UI tests in 79 files**, zero unhandled errors, and
TypeScript/Vite production build passed. The existing bundle-size warning remains.
No production code changed; the previously completed Java tiers were not rerun
for this test-fixture and documentation correction.
