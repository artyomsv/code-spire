# UnifiedDiffParser only recognizes git-style "diff --git" file sections

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-diff/src/main/java/dev/codespire/diff/UnifiedDiffParser.java:39` |
| Found during | Phase 1 code review (code-reviewer finding L1) |
| Date | 2026-07-03 |

## Issue

`parse()` skips every line until a `diff --git a/... b/...` header. Bitbucket Cloud and GitHub
emit git-style headers, so Phase 1 is unaffected — but plain `diff -u` output and some GitLab
`/diffs` per-file payloads carry only `---`/`+++` headers. Such input currently yields an empty
patch list silently (review proceeds with zero files).

## Risks

When the GitLab adapter lands (NFR-4 / roadmap "more SCM adapters"), diffs would silently parse
to nothing: reviews would post an empty summary with no findings and no error — a confusing,
hard-to-diagnose failure mode.

## Suggested Solutions

1. Add a fallback file-section detector keyed on `--- ` / `+++ ` header pairs when no
   `diff --git` line is present.
2. At minimum, log a warning when non-blank diff text produces zero patches (cheap guard).
3. Add fixture tests with headerless unified diffs before enabling any non-git-header provider.
