# M3 round 18 — operator setup usability

Measured on 2026-09-14. Presentation and usability only; all seven accepted criteria and the
recorded verifier, live item-build, GitLab networking, image and per-forge limits remain unchanged.

## Blocking fixes

- B1: before account selection, the disabled target picker says Choose a tracker account first.
  After selection with no match, it names the selected origin and links repository registration.
  The mismatch fixture has an enabled repository of the same SCM kind: only its origin excludes it.
  A positive control offers the matching repository after selecting a compatible account.
- B2: Work policy opens on the profile-version table, with Add profile and per-row New version.
  Saving inserts the returned version into that table immediately, closes the editor and announces
  the saved name/version. A fixture starts with no profiles and observes a real rendered table row,
  not an unrelated select option. Existing version names/precedence remain immutable.
- B3: both source credential pickers use accountOptionLabel. The repository role picker now uses it
  too. Duplicate-name fixtures distinguish role labels; type and disabled state come from the same
  existing helper used by SettingsContextProviders. Observed people remain a separate identity model.

## Remaining presentation findings

Work sources opens on its list or shared empty state; Add reveals creation and Cancel closes it.
The returned source appears as a text link in the list with confirmation. Work policy now has its
navigation icon; existing source/item icons remain guarded. Every setup field has help and visible
required, optional or automatic guidance. Native required constraints remain. Tracker repository
stays read-only, explains its source and how to change it, and displays the selected coordinates.
All creation/edit fieldsets retain pending-save lock-out, including Cancel. The profile form also
reuses modal-body for its card inset; a production mutation and browser measurement guard that
spacing. Profile fields explain
phase modes, precedence, lifetime, limits and the unavailable production VERIFY/LAND capabilities.

[WIDGETS.md](../../../spire-ui/docs/WIDGETS.md) inventories shared classes, helpers and working
references. Its opening tells the reader to check it before writing a new widget; CLAUDE.md links
it in Read first. The new route-derived account-options guard follows rendered children and checks
each configured-account option separately, including shared Select option labels. It discovers
actual controls before concluding, and a correct import or another correct picker cannot mask a
bare label. Restoring SettingsContextProviders to label: a.name produces one selected failure.

## A guard gap found by mutation

The first profile-table mutation survived the old table guard. Its block-comment regex mistook
the quoted /runs/* route for a comment opener and erased the following Work policy route through
the next comment terminator. Both guards now use a shared string-preserving comment reader and
accept multiline route elements. The profile table mutation then failed exactly one assertion and
passed after restoration. All four account-option contract mutations were rerun against this reader.
The initial survivor is not counted as a kill.

## Validation

- **742 UI tests in 93 files**, zero failures; TypeScript and production build passed.
- A shuffled run of App routes and both setup screens passed **72 tests**, seed 18092026.
- **28 distinct production mutations**, each one selected assertion failure, exact scratch-byte
  restoration and restored pass. No fixture was mutated. The [ledger](factory-m3-round18-mutations.json)
  records source edits, selectors and final hashes.
- Pinned Semgrep 1.172.0: zero findings and zero parser errors across **11 changed source/test files**.
- Chromium: eight screenshots of empty lists, creation, distinct missing-repository reasons and
  saved rows at 1440px/900px. Page-width assertions passed; screenshots were visually inspected.
  All HTTP API requests were intercepted, and both simulated writes used TEST-only in-memory fixtures.
  No live database row or remote tracker was changed by the browser proof.

The first full UI run found a test-helper timing error: Add was found while initial loading still
disabled it, so fireEvent clicked a disabled button. Helpers now wait for it to become enabled.
The complete suite and shuffled cases then passed; no production assertion was weakened.

The dev UI was rebuilt and recreated with --no-deps, and answers HTTP 200 on port 39285. Deployed setup-screen
source hashes match the tested worktree. No migration, run worker or local Java service test tier
was needed for these frontend changes. PR #153 remains draft for operator review.
