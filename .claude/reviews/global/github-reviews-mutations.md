# GitHub reviews — mutation checks (PR #196)

Procedure (plan `docs/superpowers/plans/2026-10-15-github-reviews.md`, Global Constraints): each mutant
breaks one guard line on a scratch branch off the feature branch, is pushed as a draft PR against
`feat/github-reviews`, and exactly one named test must fail in CI. No local builds.

| Task | Guard line | Mutant | Failing test | Run |
|---|---|---|---|---|
