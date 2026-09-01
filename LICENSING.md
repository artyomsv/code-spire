# Licensing

Code Spire is **source-available, not open source**, and deliberately split: the
libraries you build *against* are Apache-2.0, the services you *run* are FSL.

> **On the name:** "Code Spire" is a working name and the product may ship under
> a different one. This has no bearing on the terms below — the licence grants
> run from the copyright holder, Artjoms Stukans, not from the project name, so a
> rename changes branding and nothing else. The `LICENSE` files identify modules
> by their current directory names for convenience only.

## The map

| Module | License | Why |
|---|---|---|
| `spire-contract` | Apache-2.0 | The plugin SPI. Every third-party adapter compiles against it. |
| `spire-diff` | Apache-2.0 | Plugin-facing (adapters build `FilePatch`) and credits PR-Agent as prior art — see [NOTICE](NOTICE). |
| `spire-encryption` | Apache-2.0 | Generic Tink wrapper, `byte[]`-in/`byte[]`-out. No product value on its own. |
| `spire-scm-bitbucket` | Apache-2.0 | Reference SCM adapter — the worked example a plugin author copies. |
| `spire-scm-github` | Apache-2.0 | Same. |
| `spire-scm-gitlab` | Apache-2.0 | Same. |
| `spire-http` | Apache-2.0 | Shared pinned-redirect JSON client every context adapter builds on. No product value on its own. |
| `spire-context-jira` | Apache-2.0 | Reference context provider. |
| `spire-context-confluence` | Apache-2.0 | Same. |
| `spire-context-github` | Apache-2.0 | Same. |
| `spire-context-gitlab` | Apache-2.0 | Same. |
| `spire-context-code` | Apache-2.0 | Repository code context provider (ADR-026). |
| `spire-llm` | Apache-2.0 | Reference LLM provider. |
| `spire-harness` | Apache-2.0 | The agent-execution SPI. Every harness arm compiles against it (ADR-029). |
| `spire-harness-codex` | Apache-2.0 | Reference harness arm — the worked example a second arm copies. |
| `spire-workspace` | Apache-2.0 | The publisher's git library: bare clone, bundle fetch, diff, gated push. |
| `spire-runtime` | Apache-2.0 | The run-placement SPI. Every runtime arm implements it (ADR-038). |
| `spire-runtime-docker` | Apache-2.0 | Reference runtime arm — the three-container unit on a Docker daemon. |
| `spire-arch` | Apache-2.0 | Build-time architecture check (ADR-020). Tooling, not product. |
| **`spire-gateway`** | **FSL-1.1-ALv2** | Deployable service. |
| **`spire-orchestrator`** | **FSL-1.1-ALv2** | Deployable service — the deciders, sagas, event store and dashboard. This is the product. |
| **`spire-review-worker`** | **FSL-1.1-ALv2** | Deployable service. |
| **`spire-publisher`** | **FSL-1.1-ALv2** | Deployable: the sidecar that gates and pushes. The only part of a run unit holding a write credential. |
| **`spire-ui`** | **FSL-1.1-ALv2** | The dashboard front end. |
| **`spire-e2e`** | **FSL-1.1-ALv2** | Tests only, and they drive the deployables end to end. It ships no reusable surface a plugin author could build against, so the permissive case does not apply. |

Full texts: [`LICENSE`](LICENSE) (FSL-1.1-ALv2) and
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

## What FSL-1.1-ALv2 lets you do

FSL permits everything except **competing use**. In plain terms:

| You want to… | Allowed? |
|---|---|
| Self-host Code Spire and review your company's PRs | **Yes** — "internal use and access" is an explicitly named Permitted Purpose, including commercial companies |
| Fork it, modify it, run your fork | **Yes** |
| Use it in teaching or research (non-commercial) | **Yes** |
| Run it for a client as part of consulting you provide | **Yes** — named Permitted Purpose |
| Sell a hosted "AI code review" SaaS built on it | **No** |
| Bundle it into a commercial product that substitutes for it | **No** |

**Every version becomes Apache-2.0 two years after it is published.** That grant
is irrevocable and is made up front, in the license itself — nothing has to be
re-decided later, and no per-release date has to be stamped.

The plugin surface is not restricted at all: you can write and ship an SCM
adapter, context provider or LLM provider against the Apache-2.0 modules under
any license you like, including a proprietary one.

## Why the split

Design pillar #2 is *plugin-first: add a capability without touching the core.*
That promise is only credible if the thing you extend is genuinely open. So the
SPI and every reference adapter stay Apache-2.0, and the restriction lands only
on the deployables — which is also where the actual engineering value sits (the
event-driven pipeline, reconciliation, the conversation loop).

The dependency direction already supports this and must stay that way:

```
Apache-2.0 libraries  ←──depend-on──  FSL services      ✔ permissive flows into restrictive
Apache-2.0 libraries  ──depend-on──→  FSL services      ✘ never
```

No Apache-2.0 module currently depends on a service module. Keep it so — if a
library ever needs something from a service, the code moves down into a library,
not the other way round.

## Prior versions

Everything published before this change was released under Apache-2.0, and that
grant is irrevocable for those versions. Tag `v0.1.0-apache` marks the last
Apache-2.0-only commit of the full tree.

## Contributions

Contributions are accepted under the same terms as the module they touch — see
[CONTRIBUTING.md](CONTRIBUTING.md). A sign-off is required so the project can
keep offering the dual arrangement.

## Not legal advice

This page explains intent. The license files govern. If your use is near the
line, read [`LICENSE`](LICENSE) and ask your own counsel.
