# M5: harness reviews on Kubernetes — design

Status: design, 2026-11-28. Slice 0 (the spike) is done. No product code yet.

Sources were read from `origin/master` at `18ce9fc9`. Text marked **builder call** or **proposal** is the
designer's own; the operator has not ruled on it. Text marked **not verified** was not checked.

## Why

Production (`https://spired.dev`) is reviewer-only. It has no run worker, so every review there is one API call
to a model. A harness review (Codex or Claude Code as a read-only run of kind `REVIEW`, `RECONCILE` or
`FOLLOWUP`, ADR-050) holds a checkout and reads the code around the change. It is the better review.

A harness review needs a run worker, and the run worker needs a Docker daemon today
(`spire-run-worker/.../WorkerRuntimes.java` names `DockerRunRuntime` and nothing else). The Helm chart carries
no run worker (`deploy/README.md`, "Kubernetes does NOT get the run worker yet"). M5 gives the run worker a
Kubernetes runtime and puts it in the chart, so reviews run on a harness inside the cluster.

## What the operator decided (2026-11-28)

1. **A separate namespace for unit pods** (for example `spired-runs`; a chart value). Never the app namespace.
2. **API keys and Codex seats in the first delivery.** So a Kubernetes sign-in runtime is in M5, as its own
   slice after the API-key review pod.
3. **"M5 done" is the harness review lane live on spired.dev:** `REVIEW`, `RECONCILE`, `FOLLOWUP`, fork pull
   requests; Codex on a key and on a seat; Claude Code on a key. Builds, `PublicationRuntime` and the `pi`
   harness move to a later milestone. **The M5 entry in `docs/factory/ROADMAP.md` and the milestone table in
   `docs/factory/PRD.md` must be rewritten** (today: "`spire-harness-pi` … `spire-runtime-k8s` … the same work
   item runs to a pull request under both harnesses and both runtimes").
4. **Keep the egress proxy and the model proxy.** Plain standard `NetworkPolicy`. No Cilium requirement.
5. **Per-run Secrets in etcd are accepted.** Record it in `docs/SECURITY.md`.
6. **Any conformant cluster.** Standard APIs only: Pods, Secrets, ConfigMaps, `NetworkPolicy`, native sidecars
   (Kubernetes 1.29 or later). No CNI-specific feature. A cluster that does not enforce `NetworkPolicy` makes
   every run fail loudly. It never runs open.
7. **Two pods per unit and a seal check.** The agent pod's policy is "TCP to its proxy pod only" from the
   moment it is created. Init fetches the code through the proxy pod. No policy swap.
8. **A new harness stays cheap.** No per-harness code in the Kubernetes arm, the chart or the policies.
   Everything comes from the harness SPI and the agent image contract. See "Adding a harness".
9. **One harness, one module.** Each harness lives in its own `spire-harness-<name>` module. No module holds
   several. Harness modules do not depend on each other or on a runtime arm. See "Module layout".

## Goals

- A same-repository or fork pull request on a harness repository is reviewed by a unit in the cluster.
- The isolation of the Docker arm holds: the agent has no credential and reaches one host, its model proxy.
- An operator signs a Codex seat in from the dashboard, and a review is paid by that seat.
- The chart installs the run worker with a namespaced, least-privilege role.
- The same build runs on k3s with flannel and on DOKS with Cilium, with no cluster-specific switch.

## Non-goals

- Factory builds, `/fix`, verify, land, held checkpoints (`PublicationRuntime`) on Kubernetes.
- A second build harness (`spire-harness-pi`, `spire-harness-opencode`).
- Steering a running agent. A corporate CA bundle or proxy on Kubernetes (FR-F14). A private registry.
- A second run worker replica.
- Removing the Docker arm. It stays the arm for compose, dev and builds.

## Anti-goals (a failure even if it runs)

- An agent container that starts before its pod has measured its own network as closed.
- A credential value in a pod spec, a label, an annotation, an event or a log.
- A role with `pods/exec`, or with `get`/`list` on Secrets, or any cluster-wide role.
- A spec feature the arm cannot give, silently ignored.
- A branch on a harness name anywhere in `spire-runtime-k8s`, the chart or a policy.
- A branch on a CNI or a distribution name anywhere.

## What the spike measured (slice 0, 2026-11-28)

Manifests: the session scratchpad `m5-spike/` (`00-base.yaml`, `10-agent.yaml.tpl`, `run.sh`). A default-deny
policy, a proxy pod (tinyproxy) with "DNS + 80/443 to public addresses", an agent pod with "TCP 8888 to the
proxy pod only", `hostAliases` `spire-model` → the proxy pod's IP, an init `seal` loop, a native sidecar, a
Secret volume at `/inputs`.

| Cluster | Result |
|---|---|
| k3s v1.36.2, flannel + kube-router (homelab) | Policy is enforced. **But the agent pod's network was open for about 1 s after its first container started**: it reached `1.1.1.1:443`, the API server, kube-dns and `spire-db-rw` in `spired`. Then blocked for the rest. Twice the same. |
| DOKS v1.36.0, Cilium | Blocked from the first try (0 open of 160). |
| Both | HTTPS, `apk` and `git clone` through the proxy worked. The Secret at `/inputs` was readable. The native sidecar got `TERM` and the pod ended `Succeeded`. `hostAliases` worked. |

A blocked try costs its full timeout. So the seal needs short timeouts.

**What this means.** "The policy exists before the pod" is not enough on every CNI. The seal check is
required, not optional, and it must run before every other container of the pod.

## The unit: two pods

A pod has one network namespace, and a `NetworkPolicy` selects pods, not containers. On Docker the agent
cannot reach the egress proxy at all (`DockerRunNetworks.java`, class javadoc). One pod cannot keep that. So
the unit is two pods, in the run namespace:

```
anchor    ConfigMap  unit-<d>          labels + annotations only; owns everything below

Pod P  "proxy"   unit-<d>-proxy        role=proxy
  init       seal          (publisher image; proves this pod's own policy is in force)
  containers model-proxy   :8080   credential from its own Secret; HTTPS_PROXY=http://spire-egress:8888
             egress-proxy  :8888   tinyproxy, declared hosts, ENFORCE; accepts loopback only
             fetch-proxy   :8889   tinyproxy, forge host only; :8890 = "close"; exits on close or deadline
  hostAliases spire-egress -> 127.0.0.1

Pod U  "unit"    unit-<d>-unit         role=unit
  init       seal          (publisher image; proves U reaches P and nothing else)
             fetch         (publisher image; reviewer token; clones through spire-fetch:8889; then closes it)
             fetch-closed  (publisher image; proves :8889 no longer answers)
             publisher     (native sidecar, restartPolicy Always; spire-publish-none)
  container  agent         (placeholders only; calls http://spire-model:8080)
  volumes    workspace, handoff: emptyDir medium Memory with sizeLimit; inputs: Secret, agent only, read-only
  hostAliases spire-model, spire-fetch -> Pod P's IP
```

`<d>` is a digest of the run id, as the Docker arm already uses for names (`DockerRunRuntime.digestOf`). A run
id such as `run::github:<owner>/<repo>:review-…:1` is not a legal label value or object name.

Both pods: `restartPolicy: Never`, `automountServiceAccountToken: false`, `enableServiceLinks: false`, no
shared process namespace, the `restricted` Pod Security profile (non-root, no privilege escalation, all
capabilities dropped, `RuntimeDefault` seccomp). The four images already run as uid 1001 or 1002
(`deploy/agent/codex/Dockerfile:82`, `deploy/agent/claude-code/Dockerfile:58`, `spire-publisher/Dockerfile:44`,
`spire-model-proxy/Dockerfile:34`, `deploy/egress/Dockerfile:30`). The three proxies also get a read-only root
filesystem with an `emptyDir` `/tmp`, as on Docker (`EgressProxyContainer.java`, `ModelProxyContainer.java`).

### Policies

Shipped by the chart, once, in the run namespace:

| Policy | Selects | Allows |
|---|---|---|
| `default-deny` | every pod | nothing in, nothing out |
| `sign-in` | `role=sign-in` | egress: DNS, TCP 443 to public addresses |

Created by the worker, per unit, **before** either pod:

| Policy | Selects | Allows |
|---|---|---|
| `unit-<d>-unit` | `unit=<d>, role=unit` | egress: TCP 8080, 8889, 8890 to pods `unit=<d>, role=proxy`. No DNS. No ingress. |
| `unit-<d>-proxy` | `unit=<d>, role=proxy` | ingress: TCP 8080, 8889, 8890 from pods `unit=<d>, role=unit`. Egress: DNS; TCP 443 and 80 to `0.0.0.0/0` except the private, link-local and CGNAT ranges, plus configured extra CIDRs. |

A standard policy cannot say "the same label value on both sides", so the pairing needs two objects per unit.
That pairing is the point: unit A's agent must not reach unit B's model proxy and spend B's credential.

"Public addresses" is `ipBlock` `0.0.0.0/0` with `except` `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
`169.254.0.0/16`, `100.64.0.0/10`, `127.0.0.0/8`, as the spike used. DNS is UDP and TCP 53 to pods labelled
`k8s-app=kube-dns` in `kube-system`; both are chart values, because a cluster may name them differently or
run a node-local cache on a link-local address.

### The seal

`seal` is a new command in the publisher image (**proposal**: `spire-seal`). It is the first init container
of both pods. The worker passes its targets in, because the pod has no service links and no service account:

- **Canaries** (must fail): the API server's address, which the worker reads from its own
  `KUBERNETES_SERVICE_HOST` and `KUBERNETES_SERVICE_PORT`; plus any configured extra. The API server answers
  every pod on every cluster when no policy stands in the way, so "blocked" there means a policy did it. A
  public IP is a weaker canary (a cluster may have no direct route out anyway); it is an optional extra.
- **Node canaries** (must fail; **design call**): the pod's own node IP (`status.hostIP`, through the
  downward API) on the kubelet port 10250 and the API port 6443. A `NetworkPolicy` does not reliably govern
  traffic from a pod to its own node, so this is measured per run. A reachable node canary fails the run
  `RUNTIME_UNAVAILABLE`. As a second guard, unit and proxy pods get a default node affinity away from
  control-plane nodes (a chart value, overridable), so port 6443 on the pod's node is closed by placement.
- **Control** (must succeed, pod U only): TCP connect to `spire-model:8080`. It proves the pod's network is up
  and the allowance is in force. Without it, "everything fails" would read as "sealed" on a pod whose network
  is not ready yet.

A round probes every target in parallel with a 500 ms connect timeout. A canary is open only when the connect
succeeds. The seal passes after **5 rounds in a row** with every canary blocked and the control answered, 250 ms
apart (about 4 s). It fails after a **60 s** deadline, exit code non-zero, one line saying which target was
open. The numbers are config keys (**builder call** on the defaults; k3s needed about 1 s).

A failed seal fails the run `RUNTIME_UNAVAILABLE`, detail `network_policy_not_enforced`, naming the open
target. No later container of that pod starts, because init containers run in order.

Pod P's seal has no control target and probes canaries only. It closes the same 1 s window for the model
proxy's own egress. It measures egress, not ingress: that the ingress rule bites in the same instant is
**not verified** (see Risks).

### The fetch path: the choice

Three ways for init to reach the forge were weighed.

| Option | What it is | Verdict |
|---|---|---|
| A. Policy swap | U starts with public egress; the worker narrows the policy after fetch; a seal waits. | Rejected. The worker is in the loop of every start. It needs `patch` on policies. How fast a *change* bites is CNI-specific and was not measured. The spike shows a 1 s lag even for a policy that existed first. |
| B. Forge host stays allowed | One egress proxy allows the vendor and the forge for the whole run; "the token is init-only". | Rejected. The agent would reach the egress proxy. With an attacker's own token in injected text it could push the code to an attacker's repository on the forge, or call the vendor past the model proxy's path guard. On Docker the agent reaches neither (`RUN-TOPOLOGY.md` §6B.1). |
| **C. A fetch proxy that closes** | A third container in pod P: tinyproxy that allows the forge host only, on its own port. `fetch` uses it, then tells it to close. It exits. A separate init container proves the port is dead before the agent starts. | **Chosen.** |

Why C:

- U's policy is constant from creation: "TCP to P". Nothing is patched, and the worker is not in the path.
- It is measured, not trusted. `fetch` reads untrusted repository content (`docs/SECURITY.md`, the fork
  section, on JGit). If it never sends the close, `fetch-closed` fails and no agent runs.
- It closes for good. A container of a `restartPolicy: Never` pod that has exited is not started again.
- It uses only pod semantics. No CNI timing is involved after the first seal.
- The egress proxy stays unreachable from U, as on Docker: U's policy has no rule for port 8888, and tinyproxy
  there accepts loopback only.

How it closes (**proposal**): the fetch-proxy container runs tinyproxy and waits for one TCP connection on
port 8890; then it stops tinyproxy and exits 0. It also exits by itself after the init deadline (15 minutes
on Docker, `DockerRunRuntime.INIT_TIMEOUT`). `fetch` connects to `spire-fetch:8890` as its last act.
`fetch-closed` passes after 3 refused connects to `spire-fetch:8889` and fails after 30 s
(`RUNTIME_UNAVAILABLE`, `fetch_proxy_not_closed`). Anything in U may close it early; that only fails its own
fetch.

After the exit, pod P is no longer `Ready`. That is why the name `spire-model` is a `hostAliases` entry and
not a Service: a Service would drop a not-ready pod, could not carry the same name for every unit in one
namespace, and would need DNS in U, which U must not have. The pod is never restarted, so its IP holds for the
unit's life.

`fetch` gets `HTTPS_PROXY=http://spire-fetch:8889`. Today `RunUnitSpec.environmentFor` gives init the
deployment's own environment (`UnitDeployment.Placement.OUTSIDE`). The SPI must say this new placement itself;
the javadoc there forbids a per-arm copy of the environment merge. The forge host for the fetch proxy's
allowlist must also come from the spec, not from parsing init's variables (**proposal**: a `fetchHosts`
component on `RunUnitSpec`, set by `RunUnitBuilder` from the clone URL).

The fetch proxy's log is not part of `egressOf`. On Docker init's traffic is not recorded either.

### Inputs

`UnitInputs` (`context.md`, `prior-findings.md`, `thread.md`) become one per-unit Secret, mounted read-only at
`/inputs` in the agent container only, mode `0444`. No tar copy, no environment variable. The publisher never
mounts it. `UnitInputs` demands that init mounts the volume writable "where the runtime writes the inputs";
on this arm init's mount becomes an empty `emptyDir` and nothing is written through it (the javadoc needs the
rewording "the arm delivers the files").

A Secret holds at most 1 MiB. The contract's bound is 224 KiB; the runtime's is 1 MiB
(`UnitInputs.MAX_BYTES`). The arm refuses inputs above 768 KiB before any object exists (**builder call** on
the number), `BAD_COMMAND`, `inputs_too_large_for_kubernetes`.

### Credentials

`ContainerSpec.environment` does not say which variable is a secret. So the arm never classifies:
**each container's whole environment is its own Secret, read with `envFrom`** (**proposal**). Nothing from a
container's environment is written into a pod spec. A container cannot name another container's Secret, and
no pod can read the API.

| Secret | Read by | Holds |
|---|---|---|
| `unit-<d>-env-fetch` | `fetch` | the reviewer token, the fetch branch and ref |
| `unit-<d>-env-agent` | `agent` | placeholders, harness variables |
| `unit-<d>-env-publisher` | `publisher` | its variables (no write credential on a review unit) |
| `unit-<d>-env-model` | `model-proxy` | `SPIRE_MODEL_UPSTREAM`, `_INJECTION`, `_PATHS_*`, `SPIRE_MODEL_SECRET`, `SPIRE_MODEL_ACCOUNT` |
| `unit-<d>-inputs` | `agent` (volume) | the input files |

`argv` stays in the pod spec, as it is in `docker inspect` today.

**A seat's path does not change.** The orchestrator keeps the sealed sign-in file and renews it
(`SeatRenewal.java`: "The refresh token still never leaves the orchestrator"). The run command carries a copy
with the refresh token emptied. `ModelProxyEnvironment.of` takes only the access token and the account id out
of it, for `SPIRE_MODEL_SECRET` and `SPIRE_MODEL_ACCOUNT`. On this arm those two land in `unit-<d>-env-model`.
The agent gets the placeholder `auth.json` as before. The upstream and the egress host are `chatgpt.com`.

### Resources

The unit's memory and CPU limits go on the agent container (4 GiB, 2 CPUs today, `RunUnitBuilder.java:92,121`).
The disk limit (2 GiB) is the `sizeLimit` of the memory-backed volumes, as `RUN-TOPOLOGY.md` §9.7 already
decided for Kubernetes; `RunUnitBuilder` sizes disk against memory for that reason. Proxies keep their Docker
bounds (192 MiB and 64 MiB, half a CPU each). Requests are chart values, lower than limits. Kubernetes has no
per-pod pid limit field; the Docker arm's 512 has no equal here, and that is recorded, not hidden.

## Kubernetes arm of `RunRuntime`, method by method

`spire-runtime-k8s`, class `KubernetesRunRuntime implements RunRuntime` (not `PublicationRuntime`).

| Method | On Kubernetes |
|---|---|
| `type()` | `KUBERNETES`. |
| `capabilities()` | `networkPolicy=true, resourceLimits=true, steering=false, archival=false, garbageCollection=false, nativeSidecar=true`. `archival=false`: a finished pod's volumes are not kept (**not verified** what the kubelet keeps). `nativeSidecar=true`: on Docker the sentinel path is unconditional (`RuntimeCapabilities.java`), but on this arm the kubelet sends the publisher `TERM` when the agent exits, so the publisher must handle it (see "The publisher's end"). |
| `create(spec)` | Refuse what the arm cannot give (below). Then, in order: anchor ConfigMap; Secrets and the two policies, each owned by the anchor; pod P; wait until its model proxy and egress proxy pass their `tcpSocket` startup probes (120 s and 20 s, the Docker arm's waits); read P's IP; pod U. Return when the agent container is running, or fail with the cause in the table below. A second `create` for the same run id meets "already exists" on the anchor and adopts nothing: it fails, as a redelivered command is stopped earlier by `run_claim`. |
| `attach(handle, channel, lines)` | Follow the log of U's `agent` or `publisher` container from its start, split into lines, cap 64 KiB, as `DockerRunRuntime.MAX_LINE_CHARS`. A restarted worker attaches again from the start. |
| `cancel(handle)` | Delete both pods (grace 5 s, the Docker arm's stop grace). The anchor stays, so the unit is still discoverable and `cancel` is idempotent (`RunRuntimeContract`: `cancelDoesNotDestroyTheUnit`, `cancelIsIdempotent`). Logs are gone after it; a cancelled review keeps nothing. |
| `salvage(handle)` | Wait up to the drain window for U to end (the kubelet stops the publisher sidecar after the agent exits). Build `Finalization` from the publisher's exit code in the pod status. A pod that is gone gives `vanished`; one past its deadline gives `overran`. Never deletes anything. |
| `egressOf(handle)` | Read the `egress-proxy` container log of P, bounded, with the existing `ProxyLogParser`. |
| `modelProxyOf(handle)` | Read the `model-proxy` container log of P, bounded, with the existing `ModelProxyLogParser`. |
| `destroy(handle)` | Delete the anchor with foreground propagation; then list pods, policies and ConfigMaps by the unit label and delete what is left; return only when none is listed. Secrets go by ownership alone, because the role cannot list them. |
| `discoverUnits()` | List anchors by label; the run id is an annotation. The existing `OrphanWatchdog` does the rest. |
| `drainWindow()` | U's `terminationGracePeriodSeconds` plus a margin for the log read: 30 s + 30 s (**design call**, builder-tunable). `RunAckBudget` reads it from the arm. |
| `steer(...)` | Throws `UnsupportedOperationException`. |
| `describeImage(image)` | Read the registry over HTTPS (OCI distribution API): tag → manifest or index → config → labels; the pin is `repository@digest`. Anonymous token for a public image. One read for labels and pin, as the interface demands. No daemon, no pod. |

**The publisher's end (design call, slice 1).** `ReadOnlyPublisher.run()`
(`spire-publisher/.../ReadOnlyPublisher.java:40-50`) polls for `DONE`, then prints the artifact. It has no
`TERM` handler. A native sidecar gets `TERM` the moment the agent exits, so the artifact could be cut in the
middle. Ruling: on `TERM` the publisher finishes its current poll and print, bounded by the pod's
`terminationGracePeriodSeconds` (30 s). It exits 0 only when the artifact was printed whole. A `TERM` before
`DONE` exits with a named non-zero code, and `salvage` reads that code. The Docker arm's behaviour does not
change.

`describeImage` is slice 1 work (**design call**): `ModelProxyResults.imageMissing`, `ModelProxyImageCheck`
and `HarnessImageWorker` call it at startup and on every run, so no unit can start without it.

Every pod also carries `activeDeadlineSeconds` = wall clock + drain window + 300 s, as a backstop behind the
worker's own clock.

**Refused before any object exists** (`BAD_COMMAND`, a named detail, an attention row where the lane has one):

| Spec | Detail |
|---|---|
| no model proxy (mode `OFF`, egress `off`, or a harness with no route) | `kubernetes_requires_credential_proxy` |
| a host mount in `EnterpriseEnvironment` | `kubernetes_has_no_host_mounts` |
| inputs above the bound | `inputs_too_large_for_kubernetes` |
| a mount of another run's volume (`Mount.ofRun`) | `kubernetes_reviews_only` |

**design call, slice 1:** every unit that is not a proxied review unit is refused `kubernetes_reviews_only`
before any object exists, so no deploy lets a build reach this arm. `RunLauncher.java:133` already refuses a
work item on an arm that is not a `PublicationRuntime`, and `WorkVerifyWorker.java:73` answers `COULD_NOT_RUN`.
A `/fix` run is not held, so it needs the new refusal (**builder call** on the mechanism: a capability or a
marker on the spec, never a look at `type()`).

"Proxied only" is a **proposal**. It keeps one topology, and it is the safe default for a new arm: Codex and
Claude Code both declare a route, and a fork review needs one anyway (`fork_requires_credential_proxy`).

## Sign-in on Kubernetes

Today (`DockerSignInRuntime.java`): one trusted container with no mount runs the harness's sign-in command,
its output is streamed for the link and the code, and the result file is copied out of the stopped container.
"The result is copied out, never printed", because output stays in the daemon's log. The container's name is
the claim (`AlreadyClaimed`).

Kubernetes has no copy from a stopped container, and `exec` is refused by this design. So
(**proposal**) `KubernetesSignInRuntime`:

```
Pod  signin-<d>   role=sign-in   (the chart's static "sign-in" policy: DNS + 443 to public addresses)
  init       seal      (canaries only)
             courier   (native sidecar; publisher image; gets the worker's one-time PUBLIC key)
  container  signin    (the agent image; the SignInFlow command as entrypoint)
  volume     result: emptyDir medium Memory, mounted at the parent directory of resultPath in both
```

- `start`: the worker makes a key pair in memory, one per sign-in, and creates the pod. A name conflict from
  the API server is `AlreadyClaimed`; the API server is the election, as the daemon is on Docker.
- The worker streams the `signin` container's log for the link and the code, as today.
- When `signin` exits, the kubelet sends `TERM` to the courier. The courier reads the result file, encrypts it
  to the public key (hybrid encryption; Tink is already a dependency through `spire-encryption`), prints one
  line of ciphertext and exits.
- `result`: the worker decrypts that line with the private key it never wrote anywhere. The node's log holds
  ciphertext only. So the rule "a credential is never printed" holds in substance.
- `awaitExit`: the `signin` container's state from the pod status: `Observed(code)`, `StillRunning`, or
  `Unobservable` when the API cannot be read.
- `cancel` and `destroy`: delete the pod; `destroy` returns true only when a read confirms it is gone. The
  memory volume dies with the pod.
- `find`, `discover(olderThan)`: by the label `dev.codespire.signInId`'s digest and the pod's creation time.

A worker that dies mid-sign-in loses the private key. The sign-in then fails and the existing sweep says
"start it again" (`HarnessSignInWorker.destroyAbandonedUnits`). The interface's `result` javadoc warns against
an arm that remembers state; here the loss is explicit and safe, and it must be written in that javadoc.

The sign-in unit is trusted: "no repository, no workspace, no prompt and no ticket text" (`SignInUnitSpec`).
That is why it may have public egress, as it has the open bridge on Docker. `WorkerSignInRuntime` selects the
arm by the same config key as the run arm.

## Module layout

The operator's rule: one harness, one module; no module holds two harnesses; the run worker is the only place
that wires harnesses and runtimes together. Today's build files already have this shape.

| Module | Role | Depends on (of these) | Source |
|---|---|---|---|
| `spire-harness` | harness SPI and normalized types | nothing | `spire-harness/build.gradle.kts` |
| `spire-harness-codex` | one harness | `spire-harness` | `spire-harness-codex/build.gradle.kts:19` |
| `spire-harness-claude-code` | one harness | `spire-harness` | `spire-harness-claude-code/build.gradle.kts:19` |
| `spire-harness-<name>` | one harness each, later | `spire-harness` | — |
| `spire-runtime` | runtime SPI | nothing | `spire-runtime/build.gradle.kts` |
| `spire-runtime-docker` | Docker arm | `spire-runtime` | `spire-runtime-docker/build.gradle.kts:19` |
| `spire-runtime-k8s` (new) | Kubernetes arm | `spire-runtime` | — |
| `spire-run-worker` | composition root | all of the above | `spire-run-worker/build.gradle.kts:30-34` |

Rules, to be held by an architecture test (M5-AC-26):

- No `spire-harness-*` module depends on another `spire-harness-*` module or on any `spire-runtime*` module.
- No `spire-runtime-*` arm depends on any `spire-harness*` module. (`CredentialRoute` already notes "the
  harness module cannot see `spire-runtime`".)
- Only `spire-run-worker` depends on both families.

## Adding a harness

**What a new harness supplies. Nothing else changes.**

| Piece | Where | What it carries |
|---|---|---|
| A module `spire-harness-<name>` | new | one class that implements `HarnessAdapter` (`spire-harness/.../HarnessAdapter.java`) |
| `command`, `environment`, `promptDelivery` | the adapter | the agent's argv and variables; in a proxied unit, placeholders and the base URL `http://spire-model:8080` |
| `egressHosts` | the adapter | the vendor hosts the egress proxy allows |
| `credentialRoute` | the adapter | upstream origin, injection kind, exact paths, path prefixes for the model proxy (`CredentialRoute.java`) |
| `parse`, `classify`, `usage`, `capabilities` | the adapter | events, outcome, tokens |
| `signIn` (optional) | the adapter | the sign-in command, the result path, the verification host (`SignInFlow`) |
| An agent image | `deploy/agent/<name>/` | passes `spire-agent-image verify` (`docs/factory/AGENT-IMAGE-CONTRACT.md`): entrypoint, uid 1001, `/workspace`, `/handoff`, the `models` label |
| One image value | chart `factory.agentImages.<name>` | becomes the orchestrator's `spire.factory.agent-image.<name>` (a map already: `spire-orchestrator/src/main/resources/application.yml:446-447`) |
| Registration | the composition root | see below |

**What does not change:** `spire-runtime`, `spire-runtime-docker`, `spire-runtime-k8s`, the chart's templates,
the RBAC, every `NetworkPolicy`, the seal, the fetch proxy, the publisher, the egress proxy image. The
Kubernetes arm sees a `RunUnitSpec`: images, argv, environments, mounts, an `EgressPolicy` with hosts, a model
proxy container. It never sees a harness name. `spire-runtime-docker` holds no harness name today either (a
search of its main sources for the vendors' names found none).

**Registration today is an explicit list.** `spire-run-worker/.../HarnessRegistry.java:28-29`:
`byType.put(HarnessType.CODEX, new CodexAdapter())` and the same for `ClaudeCodeAdapter`, keyed by the closed
enum `HarnessType { CODEX, PI, OPENCODE, CLAUDE_CODE }` (`spire-harness/.../HarnessType.java:4`). So a new
harness is two edits beside its module: the Gradle dependency (`spire-run-worker/build.gradle.kts`) and a line
in `HarnessRegistry`; a fifth name would also need an enum entry.

**Proposal: discovery.** Each harness module ships `META-INF/services/dev.codespire.harness.HarnessAdapter`,
and `HarnessRegistry` loads adapters with `java.util.ServiceLoader`, keyed by `adapter.type()`; two adapters
with one type refuse startup. `ServiceLoader` and not CDI, because the harness modules are plain libraries
with no framework. Then a new harness is its module plus one dependency line in the run worker's build file.
The enum stays for now (**builder call**): it is on the wire and in stored rows, and making it an open name
is a contract change that M5 does not need.

**Harness knowledge that sits outside the adapters today.** None of it is in a runtime arm, but each item
would make a new harness more than "a module":

| Where | What | Proposal |
|---|---|---|
| `spire-runtime/.../ModelProxyReport.java:23` | `MODEL_CALL_PATHS` lists Codex's and Claude Code's model call paths inside the **runtime SPI**. The Kubernetes arm's log reader would inherit it. | In M5: move it to `CredentialRoute` (a `modelCallPaths` component), carry it on the unit spec, and have both arms read it from there. |
| `spire-run-worker/.../ModelProxyEnvironment.java:57`, `Credentials.java:144` | The worker reads `tokens.access_token` and `tokens.account_id` out of Codex's sign-in file for `CHATGPT_SEAT`. | Later: the adapter turns its own sign-in file into the model proxy's secret values. Not needed for M5; no other harness has a seat. |
| `spire-run-worker/.../SignInAuthMode.java` | Reads Codex's `auth_mode`. | Later, with the item above. |
| `spire-model-proxy/.../ProxyRoute.java:23`, `Injector.java:60` | The injection kinds are a closed set: `BEARER`, `X_API_KEY`, `CHATGPT_SEAT`. A vendor with another header needs a model proxy change. | Accept. It is the credential boundary; a closed set there is a feature. Say so in "Adding a harness" docs. |
| `spire-model-proxy/.../ResponseHeaders.java:38-41` | The response header allowlist names `openai-*` and `x-codex-*`. Another vendor's rate-limit headers are dropped. | Accept for M5 (dropping is safe). Later: the route may name extra pass-through headers, checked against the credential. |
| `spire-orchestrator/src/main/resources/application.yml:436-438` | Seat renewal client and URL are per harness, in config. | Already data, not code. No change. |

Only the first row is M5 work. It is the one place where harness knowledge would enter the new arm.

## RBAC

One ServiceAccount for the run worker, in the app namespace. One Role and RoleBinding **in the run
namespace**. Nothing cluster-wide.

| Resource | Verbs | Why |
|---|---|---|
| `pods` | create, get, list, watch, delete | units and sign-ins |
| `pods/log` | get | events, results, proxy records |
| `configmaps` | create, get, list, delete | anchors |
| `secrets` | create, delete | per-unit environment and inputs; **no get, list or watch** |
| `networkpolicies` | create, list, delete | per-unit pairing; **no patch** |

No `pods/exec`, no `pods/attach`, no `pods/portforward`. Unit pods have no token at all. A role that may
create pods can mount any Secret of that namespace; only per-unit Secrets live there, which is decision 1's
reason.

## Chart and deploy changes

`deploy/helm/spire` (all behind `runWorker.enabled`, default `false`; the off render must equal today's):

- `templates/run-worker.yaml`: Deployment (1 replica), Service, ServiceAccount.
- `templates/run-worker-rbac.yaml`: Role and RoleBinding in the run namespace.
- `templates/run-namespace.yaml`: the namespace (when `runWorker.runs.createNamespace`) with the label
  `pod-security.kubernetes.io/enforce: restricted`, `default-deny`, the `sign-in` policy, a ResourceQuota.
- `values.yaml`: `runWorker.runs.namespace` (required when enabled, and refused when equal to the release
  namespace); image names for the publisher, model proxy and egress proxy at the chart's `appVersion`;
  `factory.agentImages` as a **map** keyed by harness name (so a new harness is a value, not a template);
  `runWorker.reviewConcurrency`; DNS selector; extra egress CIDRs; unit requests; node selector and
  tolerations for unit pods.
- `configmap.yaml`: the keys below, and the orchestrator's `SPIRE_FACTORY_AGENT_IMAGE_*`.
- `tests/render.sh` (new assertions, and `--self-test` proving they bite), `deploy/kustomize/*`, `deploy/k8s/*`
  re-rendered, `deploy/render-manifests.sh --check`.
- The run worker's database user and its `runworker` schema, its OIDC client, and whether the dashboard needs
  a route to `/rw`: **not verified**, see "Left to the builder".

`stukans/monorepo`, `devops/apps/spired`: `values-home.yaml` turns the run worker on and names `spired-runs`;
new Secret examples for the run worker; `render.sh`; bump `VERSION`; the "Apps · Spired · deploy home"
workflow. A Keycloak client for the run worker in realm `spired`, if OIDC stays on for it.

## Config keys (names are proposals)

| Key | Default | Meaning |
|---|---|---|
| `SPIRE_RUN_RUNTIME` | none; startup refusal when unset | `docker` or `kubernetes`. The compose files and `%dev`/`%test` set `docker`, so no existing install changes. |
| `SPIRE_RUN_K8S_NAMESPACE` | none; required for `kubernetes` | where units run |
| `SPIRE_RUN_K8S_SEAL_CANARIES` | the API server's address | extra `host:port` targets that must be blocked |
| `SPIRE_RUN_K8S_SEAL_ROUNDS` / `_TIMEOUT` / `_DEADLINE` | 5 / 500 ms / 60 s | the seal |
| `SPIRE_RUN_K8S_EGRESS_EXTRA_CIDRS` | empty | private addresses the proxy pod may reach (a self-hosted forge) |
| `SPIRE_RUN_K8S_DNS_NAMESPACE` / `_DNS_LABELS` | `kube-system` / `k8s-app=kube-dns` | the DNS allowance |
| `SPIRE_RUN_K8S_UNIT_START_TIMEOUT` | 300 s | image pull and scheduling, before `IMAGE_UNAVAILABLE` or `RUNTIME_UNAVAILABLE` |
| `SPIRE_RUN_K8S_NODE_SELECTOR` / `_TOLERATIONS` | empty | where unit pods may be placed |
| `SPIRE_RUN_K8S_REQUEST_CPU` / `_REQUEST_MEMORY` | 250m / 1Gi | the agent container's requests |

Existing keys keep their meaning: `SPIRE_RUN_CREDENTIAL_PROXY_MODE`, `SPIRE_RUN_CREDENTIAL_PROXY_IMAGE`,
`SPIRE_RUN_EGRESS_IMAGE`, `SPIRE_PUBLISHER_IMAGE`, `SPIRE_RUN_REVIEW_CONCURRENCY`,
`SPIRE_RUN_MAX_WALL_CLOCK_SECONDS` (`spire-run-worker/src/main/resources/application.yml`).

## Failure causes

Only existing `RunFailureCause` values are used (`spire-contract/.../RunFailureCause.java`).

| What happened | Cause | Detail |
|---|---|---|
| a seal did not pass | `RUNTIME_UNAVAILABLE` | `network_policy_not_enforced`, the open target |
| the fetch proxy did not close | `RUNTIME_UNAVAILABLE` | `fetch_proxy_not_closed` |
| `ErrImagePull`, `ImagePullBackOff`, `InvalidImageName` past the start timeout | `IMAGE_UNAVAILABLE` | the image name, the container's role |
| unschedulable, quota refused, API error, a proxy that never listened | `RUNTIME_UNAVAILABLE` | the pod phase, the container state, exit code, OOM flag, a bounded log tail (as `SidecarStartFailure`) |
| `fetch` exited non-zero | `CLONE_FAILED` | init's own line, as today |
| the pod vanished mid-run (eviction, node loss) | `SANDBOX_LOST` | the pod's last known reason |
| the agent was OOM-killed | `AGENT_FAILED` | `OOMKilled` |
| the wall clock passed | `AGENT_TIMEOUT` | as today |
| a vendor 401 on a model call | `CREDENTIAL_REJECTED` | from `modelProxyOf`, as today |
| a refused host or path | `BLOCKED_EGRESS` | from `egressOf` and `modelProxyOf`, as today |
| a refused spec | `BAD_COMMAND` | the names in the refusal table |

## Portability

**What a cluster must have:**

- Kubernetes 1.29 or later (native sidecars).
- A network plugin that enforces `networking.k8s.io/v1` `NetworkPolicy`, egress included.
- Pods that can reach the API server's service address when no policy forbids it (true by default everywhere;
  it is the seal's canary).
- A route from pods to public HTTPS, and cluster DNS.
- The images, pullable by the nodes.
- Memory-backed `emptyDir` allowed (it is in the `restricted` profile).

**What it need not have:** a named CNI, a service mesh, a storage class, a CRD, an admission webhook,
privileged pods, `hostPath`.

**The startup self-check** (run worker, runtime `kubernetes`; each failure is a startup refusal that names
the fix, the house rule for a missing requirement):

1. The API answers and the server version is 1.29 or later.
2. The run namespace exists and is not the worker's own.
3. A `SelfSubjectAccessReview` passes for every verb in the RBAC table, and **fails** for `get secrets` and
   `create pods/exec` in the run namespace (too much is refused too).
4. `default-deny` exists in the run namespace.
5. A canary pod that runs only `seal` passes. This catches a cluster with no policy enforcement at install
   time, not at the first review. The per-unit seal stays the gate.

**Tested on:** k3s v1.36.2 with flannel and kube-router; DOKS v1.36.0 with Cilium (slice 0, by hand). Both
`amd64`. `arm64` images are published; no `arm64` cluster has run them.

**Known cluster differences the design absorbs:** the 1 s open window on kube-router (the seal); a node-local
DNS cache or another DNS label (the DNS values); a forge on a private address (extra CIDRs); a public or a
private API endpoint (the canary is the service address, not the endpoint).

## CI

House rule: no builds on the host; CI runs everything.

- **Pure tests (`testFast`)**: spec → manifests. One test per rule: no environment value in a pod spec; the
  agent mounts no Secret but its own environment and inputs; U's policy names only P; no container has a
  token; the refusal table. Each guard is mutation-checked (break the line, exactly one test fails).
- **Arm tests on kind with a policy-enforcing CNI** (**builder call**: Calico): `RunRuntimeContract`; a full
  fake unit (test agent image, as `M0WalkingSkeletonTest` builds one); from the agent container, the canaries
  and `spire-egress:8888` are blocked and `spire-model:8080` answers; the fetch port is dead after fetch;
  cancel; destroy leaves nothing; discovery after a worker restart; the sign-in courier round trip.
- **The refusal case on kind with a CNI that does not enforce policy**: every unit fails
  `network_policy_not_enforced` and no agent container is ever created; the startup self-check refuses. Which
  kind CNI truly does not enforce is **not verified** (newer kindnet may); the job must first assert that its
  cluster is open.
- kind drives the Docker daemon, so the module joins the serialised-Docker lock;
  `DockerTestsAreSerialisedTest` derives the list and must find it.
- `spire-arch`: the new module in the neutrality and licence scans; only the composition root is allowlisted.
- Chart: `render.sh --self-test` and the drift check with the run worker on and off.

## Functional requirements

Ids are stable; a retired id is never reused. Scope: **S1–S5** is the slice; **later** is out of M5.

| Id | Requirement | Scope |
|---|---|---|
| M5-FR-1 | `spire-runtime-k8s` implements `RunRuntime` with `type() == KUBERNETES` and passes `RunRuntimeContract`. | S1 |
| M5-FR-2 | `SPIRE_RUN_RUNTIME` selects the arm in `WorkerRuntimes` and `WorkerSignInRuntime`; unset or unknown refuses startup. | S1 |
| M5-FR-3 | `create` places a read-only review unit: init to completion, then the agent with the publisher as a native sidecar, on volumes that die with the unit. | S1 |
| M5-FR-4 | The model proxy and the egress proxy run in a second pod; the agent shares neither their network namespace nor their environment. | S1 |
| M5-FR-5 | The agent reaches the model proxy at `http://spire-model:8080` with no change to a harness adapter. | S1 |
| M5-FR-6 | From its creation the unit pod may open a connection to its own proxy pod and to nothing else: not the internet, DNS, the API, a node, the LAN, another unit, or the app namespace. "A node" is held by the seal's node canaries and by placement away from control-plane nodes, because a policy alone does not reliably cover a pod's own node. | S2 |
| M5-FR-7 | No container after `seal` starts until the pod has measured that rule; a seal that does not pass in time fails the run `RUNTIME_UNAVAILABLE` (`network_policy_not_enforced`). | S2 |
| M5-FR-8 | The egress proxy enforces the harness's declared hosts and is unreachable from the unit pod; `egressOf` returns the same records as on Docker. | S2 |
| M5-FR-9 | `modelProxyOf` returns the same report as on Docker (`CREDENTIAL_REJECTED`, `BLOCKED_EGRESS`). | S2 |
| M5-FR-10 | The model credential exists only in the model proxy's container; the agent gets placeholders. | S1 |
| M5-FR-11 | No environment value of any container appears in a pod spec, label, annotation or event; each container reads its own per-unit Secret. | S1 |
| M5-FR-12 | Inputs appear read-only at `/inputs` in the agent only, from a Secret, with no tar copy. | S1 |
| M5-FR-13 | Inputs up to 224 KiB are delivered; inputs above the arm's bound are refused before any object exists. | S4 |
| M5-FR-14 | `attach` streams the agent's and the publisher's stdout by line with the 64 KiB cap, and works again after a worker restart. | S1 |
| M5-FR-15 | A `RunReviewArtifact` of up to 256 KiB arrives whole through the publisher's log. | S1 |
| M5-FR-16 | `cancel` ends both pods within 30 s, is idempotent, and leaves the unit discoverable until `destroy`. | S3 |
| M5-FR-17 | `destroy` removes every object of the unit and nothing of another unit. | S1 |
| M5-FR-18 | Every object carries a digest label of the run id; the anchor carries the full run id as an annotation. | S1 |
| M5-FR-19 | `discoverUnits` lists every unit from the cluster alone; `OrphanWatchdog` reaps one a dead worker left. | S3 |
| M5-FR-20 | The wall clock ends a run as on Docker; each pod also has a hard deadline. | S3 |
| M5-FR-21 | An image that cannot be pulled fails the run `IMAGE_UNAVAILABLE` within the start timeout. | S3 |
| M5-FR-22 | `describeImage` answers labels and a digest pin from the registry (OCI manifest, config labels, digest; anonymous for a public image), with no daemon and no pod. | S1 |
| M5-FR-23 | Containers carry the unit's memory and CPU limits; the disk limit bounds its volumes. | S1 |
| M5-FR-24 | The chart deploys the run worker (off by default) with its ServiceAccount, Role and RoleBinding. | S1 |
| M5-FR-25 | The chart names every unit image at the release version; no unit pod pulls `latest`. | S1 |
| M5-FR-26 | The review lane keeps `SPIRE_RUN_REVIEW_CONCURRENCY`; the chart's quota and docs state the memory it needs. | S3 |
| M5-FR-27 | A fork review runs with the same refusals as on Docker. | S4 |
| M5-FR-28 | A spec the arm cannot serve is refused with a named detail before any object exists. | S1 |
| M5-FR-29 | A corporate CA bundle and proxy reach every container from a ConfigMap or Secret (FR-F14). | later |
| M5-FR-30 | A private registry credential becomes an image pull secret and is on no container. | later |
| M5-FR-31 | The arm implements `PublicationRuntime` (held builds, verify, land, takeover). | later |
| M5-FR-32 | Factory builds and `/fix` run on Kubernetes. | later |
| M5-FR-33 | `KubernetesSignInRuntime`: an operator signs a Codex seat in from the dashboard; the result reaches the worker encrypted and is never readable in a log. | S5 |
| M5-FR-34 | A second build harness carries a work item to a pull request. | later |
| M5-FR-35 | `cs.review-runs` has a stated partition count for a second replica. | later |
| M5-FR-36 | Init clones only through the unit's fetch proxy, which allows the forge host alone; the agent starts only after a check that the fetch proxy no longer answers. | S2 |
| M5-FR-37 | The unit namespace is never the app namespace; the chart and the worker each refuse that. | S1 |
| M5-FR-38 | The run worker's startup self-check (version, namespace, exact permissions, `default-deny`, a seal canary pod) refuses startup with a named reason. | S2 |
| M5-FR-39 | A seat review injects only the seat's access token and account id, from the model proxy's own Secret. | S5 |
| M5-FR-40 | No code in `spire-runtime-k8s`, no chart template and no policy names a harness; a new harness needs an adapter module, an image and one chart value. | S1 |
| M5-FR-41 | Any unit that is not a proxied review unit (a build, a `/fix`, a held build, a verify, an unproxied unit) is refused with a named cause before any object exists, so no deploy lets a build reach this arm. | S1 |
| M5-FR-42 | Each harness is its own `spire-harness-<name>` module; harness modules depend on no other harness module and on no runtime module; runtime arms depend on no harness module; only the run worker wires them. | S1 |
| M5-FR-43 | The model call paths whose 401 means "credential rejected" come from the harness's `CredentialRoute`, not from a list in `spire-runtime`. | S2 |
| M5-FR-44 | (**proposal**) `HarnessRegistry` discovers adapters with `ServiceLoader`; a new harness needs no edit to a Java file outside its own module. | S4 |

| M5-FR-45 | On `TERM` the read-only publisher finishes its poll and print within the grace period; it exits 0 only when the artifact was printed whole, and with a named non-zero code when `TERM` came before `DONE`. | S1 |
| M5-FR-46 | The seal also probes the pod's own node IP on ports 10250 and 6443; unit and proxy pods avoid control-plane nodes by default. | S2 |

## Non-functional and security requirements

| Id | Requirement |
|---|---|
| M5-NFR-1 | The run worker needs no Docker socket, no `hostPath`, no privileged pod and no cluster-wide role. |
| M5-NFR-2 | The run worker's Role is namespaced and holds exactly the RBAC table above: no `pods/exec`, no read of Secrets, no `patch` on policies. |
| M5-NFR-3 | Unit pods have no service account token and do not share a process namespace. |
| M5-NFR-4 | Unit pods meet the `restricted` Pod Security profile; the proxies also run with a read-only root filesystem. |
| M5-NFR-5 | The run namespace denies all ingress and egress by default; each unit adds only its own allowances. |
| M5-NFR-6 | Retired. It is now M5-FR-7 and M5-FR-38 (a cluster that does not enforce policy fails loudly). |
| M5-NFR-7 | The agent cannot reach Postgres, Redpanda, the orchestrator, the gateway or the Kubernetes API. |
| M5-NFR-8 | Inputs and credentials are per-run Secrets, never ConfigMaps. They sit in etcd for the run's life; the operator accepted this on 2026-11-28, and `docs/SECURITY.md` records it. |
| M5-NFR-9 | The worker logs object names and run ids only; existing redaction (`SecretLogFilter`, `ContainerSpec.toString`) stays in force. |
| M5-NFR-10 | Every unit image is published for `amd64` and `arm64`; only `amd64` is proven, and the docs say so. |
| M5-NFR-11 | A failed unit start reports the pod phase, the container state, the exit code, the OOM flag and a bounded log tail, as `SidecarStartFailure` does on Docker. |
| M5-NFR-12 | A ResourceQuota on the run namespace bounds what a dispatch storm can take from the cluster. |
| M5-NFR-13 | Known parity gaps are written in `docs/UNVERIFIED.md` and `docs/SECURITY.md`: no per-unit pid limit, and no kept filesystem after a failed salvage. |
| M5-NFR-14 | Domain code does not change, and `spire-arch` gains no allowlist entry except a composition root. |
| M5-NFR-15 | CI proves the arm on a kind cluster with a policy-enforcing CNI; no test needs a real cluster. |

## Acceptance criteria

Style: `docs/factory/HARNESS-REVIEWS-3-ACCEPTANCE.md`. Live checks run on spired.dev against a test repository;
test pull requests carry `TEST:` in the title. No value below is a real result.

| Id | Check | Where | Slice |
|---|---|---|---|
| M5-AC-1 | `RunRuntimeContract` passes against the Kubernetes arm. | CI, kind + policy CNI | S1 |
| M5-AC-2 | On a cluster that does not enforce policy, every unit fails `network_policy_not_enforced`, no agent container is created, and the worker refuses to start. Mutation-checked. | CI, kind without policy | S2 |
| M5-AC-3 | From the agent container: a public address, cluster DNS, the API server, the pod's own node on 10250 and 6443, `spire-db-rw.spired`, `spire-egress:8888` and `spire-fetch:8889` each fail; `http://spire-model:8080` answers. | CI and live | S2 |
| M5-AC-4 | A same-repository `TEST:` pull request on a harness repository (Codex, API key) gets a posted review; the unit's two pods were listed during the run. | live | S1 |
| M5-AC-5 | `kubectl get pod … -o yaml` of both pods holds no `env` value at all; the agent's Secret (values hidden) holds only placeholders; only the model proxy container names the model Secret. | live | S1 |
| M5-AC-6 | `run_egress` for the run holds only the declared host on port 443, allowed. | live | S2 |
| M5-AC-7 | The model proxy log names only declared paths. | live | S2 |
| M5-AC-8 | One charge row under the review's call slot, kind `REVIEW`, equal to the reported usage. | live | S4 |
| M5-AC-9 | A second commit gives one `RECONCILE` unit; the agent read `/inputs/prior-findings.md`. | live | S4 |
| M5-AC-10 | A reply gives one `FOLLOWUP` unit and one posted answer. | live | S4 |
| M5-AC-11 | Closing the pull request mid-run removes both pods within 60 s; the run ends `CANCELLED`. | live | S3 |
| M5-AC-12 | Deleting the run worker pod mid-run: the new worker finishes the run or the watchdog reaps it within `orphan-stale-after-seconds` plus one sweep; nothing with the unit's label is left. | live | S3 |
| M5-AC-13 | A key the vendor refuses fails the run `CREDENTIAL_REJECTED` and marks its pool member. | live | S2 |
| M5-AC-14 | An image tag that does not exist fails the run `IMAGE_UNAVAILABLE` and leaves nothing. | live | S3 |
| M5-AC-15 | A fork `TEST:` pull request is reviewed on Claude Code on a key; under mode `OFF` it is refused and no object is created. | live | S4 |
| M5-AC-16 | After ten reviews, `kubectl -n <runs> get pods,configmaps,networkpolicies -l dev.codespire/unit` and `get secrets` show nothing of a unit. | live | S3 |
| M5-AC-17 | `kubectl auth can-i --as=system:serviceaccount:<app>:<sa>`: no for `get secrets` and `create pods/exec` in the run namespace, and for every verb in the app namespace and at cluster scope. | live | S1 |
| M5-AC-18 | `render.sh --self-test` and `render-manifests.sh --check` pass with the run worker on and off; the off render equals today's. | CI | S1 |
| M5-AC-19 | With `SPIRE_RUN_RUNTIME=docker`, `M0WalkingSkeletonTest` and `DockerRunRuntimeIT` pass unchanged. | CI | S1 |
| M5-AC-20 | Three reviews at once with concurrency 2 run as two units, then one. | live | S3 |
| M5-AC-21 | An operator signs a Codex seat in from the dashboard on spired.dev; the pod's log holds no token (the result line is ciphertext); the pod is gone afterwards. | live | S5 |
| M5-AC-22 | A `TEST:` pull request is reviewed on that seat at cost 0, with `chatgpt.com` the only egress host and no `auth.openai.com`. | live | S5 |
| M5-AC-23 | A fetch that never sends the close fails the run `fetch_proxy_not_closed` and starts no agent. Mutation-checked. | CI | S2 |
| M5-AC-24 | The same chart and images pass M5-AC-3 and M5-AC-4 on a second cluster with another CNI (DOKS, Cilium). | live | S4 |
| M5-AC-25 | A fake harness, declared only through `HarnessAdapter` in the run worker's test sources (its own hosts, route, paths and test image), runs a unit on kind with no change under `spire-runtime-k8s` or `deploy/helm`; its model call 401 is reported `CREDENTIAL_REJECTED` from the route it declared. | CI | S4 |
| M5-AC-26 | An architecture test in `spire-arch` reads the Gradle build files and the imports: no `spire-harness-*` module depends on another `spire-harness-*` or on any `spire-runtime*` module; no `spire-runtime-*` arm depends on or imports any `spire-harness*` module; only `spire-run-worker` has both. Proven to bite by adding a forbidden dependency in the test's own self-check. | CI | S1 |
| M5-AC-27 | `CoreIsProviderNeutralTest` covers `spire-runtime-k8s` and `deploy/helm/spire/templates`: no harness or vendor name appears there. The image values file is the one allowed place. | CI | S1 |
| M5-AC-28 | A `TERM` sent to the read-only publisher right after `DONE` still yields the whole artifact and exit 0; a `TERM` before `DONE` yields the named non-zero code. Mutation-checked. | CI | S1 |
| M5-AC-29 | A build-lane command and an unproxied unit sent to a worker on the Kubernetes arm are each refused with their named cause, and no object is created. | CI | S1 |
| M5-AC-30 | A unit whose node canary answers fails `RUNTIME_UNAVAILABLE` and starts no agent. | CI | S2 |

## Slices

| Slice | Content | Proves |
|---|---|---|
| 0 | The spike. **Done 2026-11-28.** | Two pods, policy, seal, native sidecar, Secret inputs, on two CNIs. |
| 1 | The module; anchor, Secrets, both pods, `hostAliases`; `create`, `attach`, `salvage`, `destroy`; `describeImage` from the registry; the publisher's `TERM` handling; the refusal of every non-review or unproxied unit; the config switch; the chart with the run worker, RBAC and the run namespace; Codex on an API key, same-repository pull request. The seal and fetch proxy may be stubs **only on a branch**: slice 1 is not deployed to spired.dev without slice 2. | AC-1, 4, 5, 17, 18, 19, 26, 27, 28, 29 |
| 2 | `seal` with the node canaries, node affinity, the fetch proxy and `fetch-closed`, `egressOf`, `modelProxyOf`, the startup self-check. First deploy to spired.dev. | AC-2, 3, 6, 7, 13, 23, 30 |
| 3 | Cancel, supersede, wall clock, image failure, discovery and the watchdog, worker restart, quota. | AC-11, 12, 14, 16, 20 |
| 4 | `RECONCILE`, `FOLLOWUP`, forks, Claude Code, harness discovery, the second cluster. | AC-8, 9, 10, 15, 24, 25 |
| 5 | `KubernetesSignInRuntime`, a seat review. | AC-21, 22 |

Each slice ends with its entries in `docs/UNVERIFIED.md` and `docs/HISTORY.md`. At the end: ADR-052 (the
Kubernetes unit is two pods, the seal, the closing fetch proxy, proxied only), `docs/factory/RUN-TOPOLOGY.md`
(§3 and a new §6B.3), `docs/factory/ROADMAP.md` and `docs/factory/PRD.md` (M5 rewritten),
`docs/factory/EXECUTION-LAYER.md` §5, `docs/factory/ARCHITECTURE.md` §3.2 (it still shows `finalize` and
`RunSpec`), `docs/factory/MODULES.md` §8, `docs/SECURITY.md`, `deploy/README.md`, `CLAUDE.md` Status, and an
acceptance record `docs/factory/M5-REVIEW-LANE-ACCEPTANCE.md`.

## Risks

1. **Logs are the only way out of a pod.** The kubelet rotates container logs, and an evicted pod loses them.
   A lost log is a paid review with no answer. Following from the start and attaching again after a restart
   must be proven against rotation (**not verified**).
2. **The proxy pod's first second.** On kube-router a new pod is open for about 1 s. Pod P's seal measures
   egress. Whether its ingress rule bites at the same instant is not measured; until it does, another pod in
   the cluster that guessed the IP could call the model proxy. The model proxy starts only after P's seal,
   which narrows this to "ingress lags egress".
3. **Two pods can be left half-made.** A crash between the Secrets and the pods leaves a credential in a
   Secret. The anchor is created first and owns everything, so discovery and the watchdog cover it; that
   ordering is a guard to mutation-check.
4. **Memory.** A unit may use 4 GiB and its workspace counts against it. Homelab workers are at 42% to 75%
   memory (`kubectl top nodes`, 2026-11-27). A large clone can end in `OOMKilled`.
5. **The SPI is Docker-shaped in places.** Init's environment placement, the inputs wording, `cancel` keeping
   a stopped unit, and the per-container pid limit each need a Kubernetes answer written into the SPI. A quiet
   per-arm difference is how "green, documented, and did not work" happens (`docs/UNVERIFIED.md`).
6. **JGit through the fetch proxy: supported by code, not live-proven on Kubernetes.** The clone already
   honours `HTTPS_PROXY`: `CloneMain.java:58` calls `CorporateTransport.apply(env)` before it clones, and
   `CorporateTransport.java:137-150` installs a JDK `ProxySelector` from `HTTP_PROXY`, `HTTPS_PROXY` and
   `NO_PROXY` (the FR-F14 path). So `fetch` needs no new code, only the variable. The spike cloned with the
   `git` CLI, not with JGit.

## Left to the builder

- A live clone, and a fork's pull ref fetch, with JGit through the fetch proxy (the code path exists; see
  Risks, 6).
- The fetch proxy's close mechanism (a listener in the egress image, or a small command in the publisher
  image) and its place in the image contract.
- The SPI changes: init's placement behind a fetch proxy, `fetchHosts`, the reviews-only marker, the
  `UnitInputs` wording. Each is a change to `spire-runtime` that the Docker arm must still satisfy.
- Where the shared parsers and `TinyproxyConfig` live once two arms need them (`spire-runtime` must stay
  dependency-light).
- The Kubernetes client (**proposal**: fabric8, through `quarkus-kubernetes-client` in the worker).
- The courier's encryption scheme and whether a second worker replica can ever read a sign-in result.
- What `HarnessSignInWorker` expects after `cancel` (Docker keeps a stopped container; this arm deletes).
- `describeImage` for a multi-architecture index: which platform's labels, and that the pin is the index.
- The seal's defaults, after timing them on both clusters; and whether a refused connect counts as blocked.
- Which kind CNI does not enforce policy, for the refusal job.
- The run worker's database user and schema in the chart, its OIDC client, and whether `/rw` needs a route.
- What the kubelet keeps of a finished pod's volumes, before `archival` is declared.
- Secrets-at-rest encryption on the homelab is unknown; the operator accepted the Secrets either way.
