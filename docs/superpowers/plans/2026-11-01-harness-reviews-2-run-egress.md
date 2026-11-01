# Harness reviews, PR 2: an agent's network goes through an egress proxy — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the Docker arm, a run unit's agent container (the one holding the model credential and running model output
with a shell) has no route out except a per-run egress proxy. The proxy records every host the agent asks for
(`report`) or refuses every host its harness did not declare (`enforce`). A refusal fails the run with
`BLOCKED_EGRESS` naming the host. Observed hosts are stored per run, which is how the hosts Codex really calls are
measured before `enforce` is switched on.

**Architecture:** Two Docker networks per run unit: `spire-run-<id>-agent` (`--internal`, no external route) and the
outbound path. The agent joins only the internal network. A small proxy container (tinyproxy on alpine, our own image
`spire-egress-proxy`, built like the publisher) joins both, listens on the internal side, and chains to the
corporate `HTTPS_PROXY` when one is configured. init and publisher keep today's outbound access: they run our own code
and hold the forge credential, which the agent never sees. The agent's `HTTPS_PROXY`/`HTTP_PROXY` point at the proxy by
container name; `NO_PROXY` is empty for the agent.

**Tech Stack:** docker-java (`spire-runtime-docker`), Quarkus run-worker, Flyway (orchestrator), tinyproxy.

**Spec:** `docs/superpowers/specs/2026-10-30-harness-reviews-design.md` ("The untrusted-code risk, and the operator's
answer"; "Final design review": hosts are measured, report mode first, the proxy chains to the corporate proxy, no
`auth.openai.com`; delivery step 2). `docs/factory/EXECUTION-LAYER.md` §5 and §5.2. Tech debt
`techdebt/spire-runtime-docker/2-3-a-run-unit-has-no-network-so-it-is-neither-isolated-nor-reachable.md`.

## Global Constraints

- No `seccomp=unconfined`, no added capabilities, no-new-privileges stays on every container; the proxy container gets
  `CapDrop ALL` too.
- The allowlist comes from the harness (code), never from the reviewed repository or the ticket.
- No secrets in container labels, logs or the observed-host records (hosts and ports only).
- No local build/test/lint (CI only). Docker-driving tests stay under the `dockerDaemonLock` (`DockerTestsAreSerialisedTest`).
- Commits: imperative, ≤ 72 chars, no AI attribution.

## Decisions (designer's calls; the operator ruled only "close the network first")

- **Modes:** `spire.run.egress.mode` = `off` | `report` | `enforce`, default `report`. `off` keeps today's behaviour (a
  kill switch). `enforce` is global for now; per-repository extra hosts (package registries a factory build needs,
  §5.2) are a later PR, so `enforce` is meant first for review runs (PR 4), not for factory builds.
- **Scope of isolation in this PR:** the agent container of build, held and read-only units. Verify check containers
  (no credential) and sign-in containers (operator-attended) are out of scope and listed in `docs/UNVERIFIED.md`.
- **Declared hosts:** `HarnessAdapter.egressHosts(HarnessInvocation)` → `Set<EgressHost(host, port)>`. Codex declares
  the model API for an API key (`api.openai.com:443`) and the ChatGPT backend for a seat (`chatgpt.com:443`); the
  values are confirmed by the report-mode measurement (Task 4) before `enforce` is recommended. `auth.openai.com` is
  never declared.
- **Recording:** the proxy logs each CONNECT/GET target; the run-worker reads the proxy's log when the agent ends,
  parses `(host, port, allowed)` and reports them on the run result (`RunResult` gains `egress: List<EgressAttempt>`;
  wire record with a wither, per CLAUDE.md). The orchestrator stores them in `run_egress(run_id, host, port, allowed,
  first_seen_at)` (V106). In `enforce`, a refused attempt makes the run fail with `BLOCKED_EGRESS` and the detail
  names the host (the cause exists already and nothing produces it today).
- **DNS:** the agent resolves only through Docker's embedded DNS on the internal network; it reaches the proxy by
  name. Outbound names are resolved by the proxy. (Measured in Task 4; if the embedded DNS answers external names on an
  internal network, that is harmless: there is still no route.)
- **Corporate proxy:** when `spire.run.https-proxy` is set, tinyproxy gets `Upstream http <host:port>`; the agent's
  enterprise proxy variables are replaced by the egress proxy's address (the corporate CA bundle mount stays).
- **Cleanup:** the networks and the proxy container carry the run's label and are removed with the unit (destroy,
  orphan watchdog).
- **Kubernetes:** out of scope (M5's runtime brings a real NetworkPolicy); `RuntimeCapabilities.networkPolicy`
  stays false on Docker; its javadoc's dead tech-debt reference is corrected.

## Review Focus

1. The agent container must have no network but the internal one (no default bridge), in every unit kind in scope.
2. A host outside the harness's declaration must be refused in `enforce` and recorded in `report`.
3. The forge credential's containers (init, publisher) must never share the agent's network.
4. A unit's networks and proxy must be removed on destroy and by the orphan watchdog.
5. A corporate proxy must still be reached (chained), and its credentials must not appear in records.

---

### Task 1: Failing tests
- `DockerRunRuntimeIT` (Docker tier): an agent container in `report` mode is attached only to the run's internal
  network; a request from it to a host on the default bridge (the test origin) fails; a request through the proxy to an
  allowed host succeeds and is recorded; in `enforce` a non-declared host is refused and recorded as refused; destroy
  removes the networks and the proxy.
- `RunUnitBuilderTest`: the agent's proxy variables point at the egress proxy; init/publisher keep the enterprise
  values; `off` leaves today's spec.
- `CodexAdapterTest`: declared hosts per payment kind.
- Run-worker result mapping: a refused attempt in `enforce` → `BLOCKED_EGRESS` naming the host.
- Orchestrator: `run_egress` rows from a result; a schema test for V106.
- Push; draft PR; CI fails on exactly these.

### Task 2: Implementation (the network field on `RunUnitSpec`, the proxy image under `deploy/egress/`, the runtime's
networks and proxy, the builder, the adapter declaration, the result wire field, the orchestrator store).

### Task 3: Mutation check (attach the agent to the default bridge too → the isolation test fails), revert; docs:
`docs/SECURITY.md` (egress paragraph), `docs/factory/EXECUTION-LAYER.md` §5 (what the Docker arm now enforces),
`docs/SMOKE-TEST.md` Mode R (the proxy), `docs/UNVERIFIED.md` (verify and sign-in containers), CLAUDE.md build
section (`deploy/egress/build.sh`), tech debt 2-3 updated (agent isolated; verify/sign-in remain).

### Task 4: Measurement on the dev stack (report mode): one factory run on a seat and one on an API key on
`spire-test`; record the observed hosts; set Codex's declaration to exactly the measured hosts; record the result and
date in `docs/factory/RUN-TOPOLOGY.md`. Also record whether credential injection is feasible (the input to PR 3):
the API-key path through a plain-HTTP model endpoint (`model_providers.<id>.base_url`), the seat path through
`chatgpt.com` (needs TLS termination).

## Changes after the plan check (they override the sections above)

1. **Enterprise environment splits.** `EnterpriseEnvironment` becomes trust (the CA mount and `SSL_CERT_FILE`,
   `GIT_SSL_CAINFO`, `NODE_EXTRA_CA_CERTS`: every container) and proxy (`HTTP(S)_PROXY`, `NO_PROXY`, both cases: init,
   publisher, verify and the egress proxy's upstream). `RunUnitSpec` gains an egress field; with egress on,
   `environmentFor(agent)` yields the egress proxy's address and an empty `NO_PROXY`, so `requireNoEnvironmentCollision`
   never sees two values for one key. `RunUnitBuilderTest.theCorporateEnvironmentReachesEveryContainerOfTheUnit` is
   rewritten to the new rule (trust everywhere, corporate proxy everywhere but the agent).
2. **Names are digests.** Networks and the proxy container are named `"spire-" + digestOf(runId) + "-agent"` /
   `"-proxy"`, as `DockerRunRuntime.volumeName` already does; the illegal-character cases join
   `aVolumeNameIsLegalForTheDaemonWhateverTheRunIdContains`.
3. **`RunUnitSpec` gets withers, no shorter constructor.** The five `new RunUnitSpec(` sites in `RunUnitBuilder`
   (126, 172, 180, 261, 285 — `publication()` rebuilds a held run's publisher on resume) carry the egress field through
   `withPublisher`/`withInit`-style withers.
4. **Cleanup covers every path.** Networks carry `RUN_ID_LABEL` (and the publication-hold label for held units);
   `destroy` and `destroyHeld` remove them after the containers; `cancel` stops the proxy role too;
   `everyPartOfAUnitIsDestroyedByLabelWithNoMemoryOfIt` asserts no labelled network remains. The network code lives in a
   new `DockerRunNetworks` class (`DockerRunRuntime` is past the size guideline).
5. **Hosts, not host:port.** The declaration is a set of host names; allowed ports are one global set (443, 80).
   tinyproxy runs with `Filter` + `FilterDefaultDeny Yes` (enforce) or no filter (report), `LogLevel Connect`,
   `LogFile /dev/stdout`, and its own small memory limit.
6. **The proxy log is read between salvage and interpret** through a new runtime method `egressOf(handle)` (a bounded,
   non-follow read like the init log), not a third `LogChannel`. The text is agent-controlled: parse strictly, cap the
   size, scrub with `RunFailures.scrubFor`.
7. **Networks, spelled out.** init and publisher stay on the default bridge (no `NetworkMode` change), so
   `M0WalkingSkeletonTest`'s origin and the e2e forge stay reachable. The proxy is created on `bridge` and connected to
   the run's internal network (`connectToNetworkCmd`) before start; the agent is created on the internal network only.
8. **Ranking.** A refused attempt is always recorded on the result. `BLOCKED_EGRESS` becomes the failure cause only when
   the run failed anyway (the refusal is the likely reason); a run that finished or pushed keeps its outcome and its
   pushed ref, with the refused hosts visible beside it. `RunFinished`, `RunFailed` and `RunWorkReady` gain `egress`
   (withers, `contract-schema.txt` re-baselined); the orchestrator's mapper ignoring unknown properties is checked
   before an old/new mix is relied on.
9. **Smaller.** The design doc's "an internal network has no DNS" is corrected (Docker's embedded DNS serves container
   names there). `RuntimeCapabilities.networkPolicy`'s javadoc points at the right tech debt. `docs/UNVERIFIED.md:270`
   is amended, not duplicated. V106 is the next migration. Task 4's credential-injection feasibility is recorded as a
   note for PR 3, not part of this PR's acceptance.
