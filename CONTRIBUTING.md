# Contributing to Code Spire

Issues, bug reports and pull requests are welcome. Read this file before opening
a PR — the licensing section is not boilerplate.

## Licensing of contributions

Code Spire is licensed per-module: the libraries and plugin SPI are Apache-2.0,
the runnable services are FSL-1.1-ALv2. See [LICENSING.md](LICENSING.md).

By submitting a contribution you agree that:

1. **Your contribution is licensed under the license of the module it touches.**
   A patch to `spire-contract` is Apache-2.0; a patch to `spire-orchestrator` is
   FSL-1.1-ALv2.

2. **You additionally grant the project maintainer a perpetual, worldwide,
   non-exclusive, royalty-free, irrevocable license to reproduce, modify,
   distribute and sublicense your contribution under any license terms**,
   including licenses different from the one above.

Point 2 exists so the project can keep its licensing coherent — for example
moving a module from FSL to Apache-2.0, or offering a commercial license — without
having to track down every past contributor. It does **not** take anything away
from you: you keep the copyright in your contribution and may use it however you
like elsewhere.

If you are contributing on behalf of an employer, make sure you have the
authority to make that grant.

## Developer Certificate of Origin

Every commit must be signed off, certifying the
[DCO](https://developercertificate.org/) — that you wrote the code or otherwise
have the right to submit it:

```bash
git commit -s -m "your message"
```

which appends:

```
Signed-off-by: Your Name <your.email@example.com>
```

Commits without a sign-off cannot be merged.

## Before you open a PR

```bash
./gradlew build                              # unit + per-service split tests
cd spire-ui && npm test && npx tsc --noEmit  # front end
```

- **Read `docs/` first.** `PRD.md`, `ARCHITECTURE.md`, `CONTRACT.md` and
  `DECISIONS.md` are the source of truth; a PR that contradicts a locked ADR
  needs a new ADR explaining why, not a silent change.
- **The core stays provider-neutral.** `spire-arch` fails the build if a core
  module names an SCM or context provider outside the allowlisted composition
  roots (ADR-020). If your change needs a new allowlist entry, say why in the PR.
- **Everything between components is an async event or command** — the only
  synchronous edge is webhook ingress returning 202 (ADR-004).
- **No new provider-name string literals in core.** Add capabilities as plugins.
- **Tests come with the change.** Bug fixes come with a test that failed before.
- **No dependency on a service module from a library module** — that would break
  the licensing split. See [LICENSING.md](LICENSING.md).

## Writing a plugin instead

You do not need to contribute to core to extend Code Spire. The plugin surface
(`spire-contract`, plus `spire-diff` for SCM adapters) is Apache-2.0, so you can
write and ship an SCM adapter, context provider or LLM provider under any license
you choose, including a proprietary one. The existing `spire-scm-*`,
`spire-context-*` and `spire-llm` modules are Apache-2.0 reference implementations
to copy from.

## Commit messages

Imperative mood, max 72 characters on the first line, body for anything
non-trivial explaining *why*. Reference the issue number when there is one.
