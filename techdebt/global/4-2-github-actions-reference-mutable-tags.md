# GitHub Actions are referenced by mutable tag, not by commit SHA

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | All nine workflows in `.github/workflows/` |
| Found during | Semgrep baseline measurement while adding CI, 2026-08-05 |
| Date | 2026-08-05 |

## Issue

Every third-party action is referenced by a moving major tag — `actions/checkout@v4`,
`docker/build-push-action@v6`, `aquasecurity/trivy-action@0.28.0` and so on. Semgrep's
`github-actions-mutable-action-tag` flags 45 such references.

A tag is mutable. Whoever controls the action's repository can move `v4` to any commit, and the next CI
run executes it with that job's permissions — which for `docker.yml` and `release.yml` includes
`packages: write` and, for releases, `id-token: write` and `contents: write`. The supply-chain concern
is not hypothetical: it is the mechanism behind several real Actions compromises.

The fix is to pin each reference to a full commit SHA with the version in a trailing comment:

```yaml
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11  # v4.1.1
```

## Why it was not done here

Adding it to the change that introduced CI would have meant pinning 45 references and verifying each
SHA, inside a change whose subject was "the repository has continuous verification at all". That is the
discipline-mismatch trap: adopting a reference project's structure and its stricter posture in one
step, so a failure in either is indistinguishable from a failure in the other.

It also interacts with a decision not yet made. SHA pins are only safe if something keeps them current
— an unmaintained pin is a permanently unpatched action — so this wants Dependabot's
`github-actions` ecosystem doing the updating. That is now configured, which makes this cheap to do as
its own change.

## Risks

Low, on three counts:

- **Only first-party and widely-used actions are referenced** (`actions/*`, `docker/*`,
  `github/codeql-action`, `azure/setup-helm`, `helm/kind-action`, `sigstore/cosign-installer`,
  `gitleaks/gitleaks-action`, `aquasecurity/trivy-action`, `softprops/action-gh-release`,
  `dependabot/fetch-metadata`). None is an abandoned single-maintainer action.
- **`pull_request` runs on a fork carry a read-only token** and no secrets, so the blast radius is the
  `master`-triggered workflows.
- **`trivy-action` is already pinned to an exact release** (`0.28.0`), and `dependabot-auto-merge.yml`
  is scoped to patch-level updates only.

The real exposure is `release.yml`, which holds `packages: write` and `id-token: write` — a compromised
action there could publish or sign an image. That job is the one to pin first if this is done
incrementally.

## Suggested Solutions

1. **Pin every reference to a SHA**, version in a trailing comment, in its own commit. Dependabot's
   `github-actions` ecosystem keeps them current and its PRs show the version change in the comment.
2. Pin `release.yml` and `docker.yml` only, and leave the read-only workflows on tags. Cheaper, and
   covers the jobs that actually hold write scopes.
3. Add `zizmor` or `actionlint` to CI so a new unpinned reference is caught rather than accumulating.

Once done, `semgrep.yml` can drop from report-only to `--error`, provided the two pre-existing findings
are also resolved — a formatted SQL string in `AttentionQueries` and `detect-insecure-websocket`
matching `ws://` inside a comment in `vite.config.ts`.
