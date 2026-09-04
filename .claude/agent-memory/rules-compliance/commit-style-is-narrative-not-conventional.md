---
name: commit-style-is-narrative-not-conventional
description: This repo deliberately uses narrative imperative commit subjects, not Conventional Commits — do not flag the missing type prefix above LOW
metadata:
  type: project
---

Code Spire commits use a narrative imperative subject ("Give a failed run a cause from a closed
set") with a long "why" body. They do **not** carry the `<type>(scope):` prefix that
`~/.claude/rules/git-workflow.md` specifies, and this is consistent across 100+ commits rather than
per-commit drift.

**Why:** the user's global `CLAUDE.md` Git Workflow section requires only imperative mood, a 72-char
subject, a body for non-trivial changes, and no AI-authorship mention. It does not require a type
prefix, and per that file's own precedence line it outranks `~/.claude/rules/`. `git-workflow.md`'s
`paths:` frontmatter also matches none of the files a normal Java/SQL change touches, so the rule
usually does not load by path-matching either.

**How to apply:** report the missing prefix at LOW as a house-style note, or omit it, and recommend
keeping it. Never raise it as HIGH or MEDIUM. Do still check the parts that DO bind every round:
imperative mood, 72-char subject, and above all the global prohibition on any AI/agent authorship
mention — no `Co-Authored-By`, no model names (Opus/Sonnet/Haiku/Fable/GPT/Gemini), no vendor names,
no "generated with", no review rounds presented as authorship. See
[[factory-review-debt-deletions-need-location-check]].
