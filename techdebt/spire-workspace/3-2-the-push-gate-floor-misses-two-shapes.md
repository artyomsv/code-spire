# The push gate's floor misses `.tekton/**` and a symlink at a protected directory's parent

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-workspace/src/main/java/dev/codespire/workspace/ProtectedPaths.java`; `PublishRepo.safe` |
| Found during | PR #96 whole-PR review (security L3) |
| Date | 2026-09-03 |

## Issue

The floor refuses a run that touches CI configuration — the paths that would let an agent edit the
pipeline reviewing it. Two shapes it does not judge:

1. **`.tekton/**` is absent.** OpenShift Pipelines-as-Code executes `.tekton/*.yaml` from the pull
   request head, on a cluster runner, with secrets. That is the same shape the floor exists for.
2. **A symlink committed AT a protected directory's parent.** `.github` -> `payload/` is judged as
   the path `.github`, which no floor glob matches — the globs need the `.github/workflows/` prefix.
   Whether a forge honours such a symlink is forge-specific (Jenkins does, via checkout).

The rest of the gate is sound: `..`, absolute paths, backslashes, NUL, CR and LF are refused before
any glob runs; matching is case-insensitive with `UNICODE_CASE`; both sides of a rename are judged;
the tip tree is compared against the base regardless of history.

## Risks

Both are narrow — the first needs a Tekton deployment, the second a forge that follows the symlink.
Neither is theoretical: the floor's whole purpose is that an agent must not be able to edit what runs
its own output.

## Suggested Solutions

1. Add `.tekton/**` to the floor.
2. Refuse any tree entry of mode `120000` whose path is a prefix of a floor directory glob.

Worth deciding separately, and NOT a floor entry: harness-instruction files (`AGENTS.md`,
`CLAUDE.md`, `.codex/`) are a self-steering vector for the NEXT factory run on the same repository.
That is a profile candidate rather than a hard refusal, because a repository legitimately edits its
own contributor docs.
