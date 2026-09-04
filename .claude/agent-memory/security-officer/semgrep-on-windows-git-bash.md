---
name: semgrep-on-windows-git-bash
description: How to invoke the Semgrep Docker scan in this repo on Windows/Git-Bash without the mount path being mangled, and how to read its counts
metadata:
  type: project
---

Run the Semgrep SAST step with `MSYS_NO_PATHCONV=1` and an explicit absolute Windows path on the
`-v` mount, not `$(pwd)`:

```
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "E:/Projects/Stukans/code-spire-worktrees/<worktree>/<module>:/src" -w /src \
  semgrep/semgrep semgrep scan --config p/java --config p/owasp-top-ten --json --quiet .
```

**Why:** the Bash tool here is Git Bash (MSYS). It rewrites the `/src` argument, so a plain
`-v "$(pwd):/src" ... /src/<module>` reaches Semgrep as `C:/Program Files/Git/src/<module>` and the
scan fails with `Invalid scanning root` after a full image pull — it looks like a Semgrep bug, not a
path bug. Mounting the module directory directly and scanning `.` also avoids the worktree's
`.git` file confusing Semgrep's `safe.directory` probe.

**How to apply:** any security review in this repo that runs the Step 2 Semgrep scan. Three more
things learned on the 2026-09 software-factory rounds:

- For a ref-under-review, copy the changed files with `git show HEAD:<path> > <scratchpad>/semgrep-src/<path>`
  (a loop over `git show --name-only --format= <sha>`), mount THAT directory, scan `.`.
- `paths.scanned` is smaller than the files copied and that is expected: Semgrep's default
  `.semgrepignore` skips `test/` and `tests/` directories, so every `src/test/java` file is
  dropped (13 of 33 Java files in round 3). Report main-source count + config files as scanned,
  and say the tests were read by hand — do not call the shortfall an invalid scan.
- `python` is NOT on the Git Bash PATH; parse `semgrep.json` with PowerShell
  (`Get-Content -Raw | ConvertFrom-Json`, then `.paths.scanned.Count`, `.results`, `.errors`)
  or with `node -e` (on PATH via spire-ui's toolchain; verified 2026-09-02).

Related: [[security-review-round-format]]
- To get a `src/test/java` file scanned anyway, copy it into the scratch mount under a path with no
  `test`/`tests` segment (e.g. `semgrep-src/arch/Foo.java`); `.semgrepignore` matches directory
  names, not content. Verified 2026-09-02: `paths.scanned` then listed the test file.
- The scratchpad is SHARED by every sibling agent of one session (sec-t0..sec-t4 all write there).
- Passing the files as EXPLICIT `/src/...` targets (not `.`) bypasses `.semgrepignore`, so
  `src/test/java` files ARE scanned that way — verified 2026-09-04, `paths.scanned` = 6/6 including
  three tests. Explicit targets are also the recipe the agent prompt prefers; use them and skip
  the rename trick.
  A reused `semgrep-src` dir held 28 files from other rounds when this ref changed 15 — name the
  scan dir after the ref (`semgrep-<sha>`) and `rm -rf` it first, or the scanned count is wrong.
- **`p/bash` does not exist** (registry answers HTTP 404), and ONE invalid `--config` makes the
  whole scan exit 0 with `paths.scanned: []` and `results: []` — an INVALID scan that reads as
  clean. `p/dockerfile` does exist. Verified 2026-09-03 on the whole-PR #96 scan (263 files):
  always read `errors[]` in the JSON before trusting a zero-results run.
