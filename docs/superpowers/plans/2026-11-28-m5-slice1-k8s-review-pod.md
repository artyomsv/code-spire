# M5 Slice 1: One Safe Review Pod on Kubernetes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A harness review (Codex on an API key, same-repository pull request) runs as two pods in a Kubernetes
run namespace, with the Docker arm's isolation, proven in CI on kind and live in a homelab scratch namespace.

**Architecture:** A new module `spire-runtime-k8s` implements `RunRuntime`. A unit is an anchor ConfigMap that
owns per-container Secrets, two NetworkPolicies, a proxy pod (model proxy, egress proxy, closing fetch proxy)
and a unit pod (seal, fetch, fetch-closed, publisher sidecar, agent). Pure builders turn a `RunUnitSpec` into
manifests; `KubernetesRunRuntime` applies them and reads pod logs. The run worker selects the arm by config.

**Tech Stack:** Java 25, Quarkus 3.38.3, Gradle Kotlin DSL, fabric8 `kubernetes-client`, JUnit 6, kind +
Calico in GitHub Actions, Helm.

**Spec:** `docs/superpowers/specs/2026-11-28-m5-kubernetes-review-runtime-design.md` (approved, `7976ff8f`).
Read it first. Slice 1 is its "Slices" row 1. Ids `M5-FR-n` / `M5-AC-n` below are that document's.

## Global Constraints

- **No builds, tests or linters on the host.** No `./gradlew`, `npm`, `helm template`, `kind` locally. Every
  "run the test" step is: commit, push, then read CI: `gh pr checks <n> --watch`; on red,
  `gh run view <run-id> --log-failed`. Never report a result you did not read in a CI log.
- **CI tiers:** `testFast` (Docker-free), `testServices`, and the new `testKubernetes` (Task 1). A module with
  tests is in exactly one tier (`TestTierCoverageTest`).
- Java 25, Quarkus 3.38.3, Gradle Kotlin DSL. 4-space indent. Explicit types, no `var`.
- `spire-runtime` and `spire-runtime-k8s` stay framework-free: no Quarkus, no CDI imports.
- No harness or vendor name in `spire-runtime-k8s` or `deploy/helm/spire/templates` (M5-FR-40).
- No `pods/exec` anywhere. No `get`/`list` on Secrets. No `patch` on NetworkPolicies.
- No environment value of any container in a pod spec. Each container reads its own Secret with `envFrom`.
- Test data uses `TEST-` placeholders (`TEST-owner/TEST-repo`, `TEST-secret`). No real-looking fake values.
- Every guard named below is **mutation-verified**: break the production line, push, confirm exactly one
  test fails in CI, restore. Record the failing test name in the PR description.
- Commits: imperative, first line ≤72 characters, body for non-trivial changes, `Part of #391`. No closing
  keywords. No mention of AI or tools.
- **One pull request** for the slice, not a draft, opened in Task 1 with the first commit that needs CI.
  Branch: `feat/m5-k8s-review-runtime`. After each push that answers review findings or red CI, run
  `node "E:/Projects/Stukans/dotai/shared/skills/pr-review/scripts/pr-review.mjs" request-review --pr <n>`.
- A new module gets its own `LICENSE` (first line names the module) and a row in `LICENSING.md`
  (`ModuleLicensingIsDeclaredTest`), an `include` in `settings.gradle.kts`, and a `COPY` line in the root
  `Dockerfile` (`ImageBuildSeesEveryModuleTest`).
- Docs: plain English, short sentences. Never call the project "open source".

## Review Focus

Failure modes the spec implies and no acceptance criterion names. Each has a test in its owning task.

1. **A run id that is not a legal Kubernetes name** (`run::github:TEST-owner/TEST-repo:review-1-abc:1`, or
   200 characters long): every object name is legal and ≤63 characters; two ids that differ in one character
   get different names. → Task 5.
2. **A crash between "Secrets created" and "pods created":** the unit is still found by `discoverUnits` and
   `destroy` leaves nothing. → Task 9.
3. **An agent line longer than 64 KiB, or a last line with no newline:** the long line is cut at the cap and
   later lines still arrive; the last line arrives when the container ends. → Task 8.
4. **A pod that never starts** (image missing, unschedulable): `create` fails within the start timeout with
   the pod's state in the detail, and leaves the unit discoverable, not half-made and hidden. → Task 8.
5. **`cancel` while `create` is still between the proxy pod and the unit pod:** no unit pod appears
   afterwards, and a second `cancel` does nothing. → Task 9.

## Rulings where the spec left a choice or conflicted

- **Client library: fabric8.** See Task 0.
- **CI cluster: kind + Calico** (policy enforced) and **kind + flannel** (no policy) for M5-AC-2.
- **The `fetch-closed` container sends the close itself, then verifies.** The spec says `fetch` sends it. The
  safety property is the measurement, not who sends. This way `CloneMain` does not change and the arm appends
  nothing to a container it does not own. M5-AC-23 becomes: "a fetch proxy that ignores the close fails the
  run `fetch_proxy_not_closed`".
- **Arm-owned containers take their settings as arguments,** not environment, so the rule "no environment
  value in a pod spec" needs no exception.
- **`spire-runtime-k8s` is in a new fourth tier,** `kubernetesTestModules`, because a module may be in one
  tier only and its integration tests need a cluster.
- **`TinyproxyConfig` moves to `spire-runtime`** (both arms need it). The two log parsers stay until slice 2.
- **The build-lane refusal is a method on the SPI** (`RunRuntime.buildLaneRefusal()`), read by the dispatcher.
- **The arm knows a review unit by its shape:** it has inputs (`RunUnitSpec.inputs() != null`; a review
  always has the volume, even empty) and a model proxy. Anything else is refused. No new marker on the spec.
- **Node selector and tolerations for unit pods are not in slice 1.**

## File structure

```
spire-runtime/src/main/java/dev/codespire/runtime/
  RunUnitSpec.java            modify: fetchHosts, fetch constants, environmentBehindFetch
  UnitDeployment.java         modify: Placement.BEHIND_FETCH
  RunRuntime.java             modify: buildLaneRefusal()
  UnitInputs.java             modify: javadoc only
  TinyproxyConfig.java        moved from spire-runtime-docker, public, Listener added
spire-publisher/src/main/java/dev/codespire/publisher/
  SealMain.java, Seal.java                 new: spire-seal
  FetchClosedMain.java, FetchClosed.java   new: spire-fetch-closed
  ReadOnlyPublisher.java, ReadOnlyPublisherMain.java   modify: --stay-until-term
spire-publisher/docker/spire-seal.sh, spire-fetch-closed.sh   new
deploy/egress/spire-fetch-proxy.sh         new, added to the egress image
spire-runtime-k8s/                         new module, package dev.codespire.runtime.k8s
  KubernetesRuntimeConfig.java   record: namespace, images, seal targets, CIDRs, timeouts, requests
  UnitNames.java                 digest, object names, labels, annotations
  UnitRefusals.java              what the arm refuses, with its detail
  ContainerResources.java        the spec's resources table
  UnitSecrets.java               Secret manifests (environment per container, inputs)
  UnitPolicies.java              the two NetworkPolicies
  ProxyPod.java, UnitPod.java    pod manifests
  PodLogs.java                   follow one container's log by line
  UnitStartFailure.java          pod state -> failure detail
  RegistryImageReader.java       describeImage over the OCI registry API
  KubernetesRunRuntime.java      the SPI implementation
spire-run-worker/src/main/java/dev/codespire/runworker/
  WorkerRuntimes.java, WorkerSignInRuntime.java   modify: select by spire.run.runtime
  KubernetesRuntimeSettings.java   new: config -> KubernetesRuntimeConfig
  SealCanaryListener.java          new: the worker's canary port
  RefusingSignInRuntime.java       new: sign-in is slice 5
  RunDispatcher.java, RunUnitBuilder.java   modify
deploy/helm/spire/templates/run-worker.yaml, run-worker-rbac.yaml, run-namespace.yaml   new
.github/workflows/ci.yml          modify: jobs "kubernetes" and "kubernetes-open"
```

---

### Task 0: Decide the client library and confirm its coordinates

No code. The decision is made here; the task only confirms what the build will pin.

**Decision: fabric8 `io.fabric8:kubernetes-client`.**

- Quarkus's own extension, `io.quarkus:quarkus-kubernetes-client`, is fabric8. The run worker is a Quarkus
  service, so the client is injected and its version is managed by the Quarkus platform already in the build.
- The official `io.kubernetes:client-java` would be a second HTTP stack and a second version line beside it.
- fabric8 is Apache-2.0, as `spire-runtime-k8s` is. The official client is Apache-2.0 too; licence does not
  separate them.
- fabric8 ships a mock API server (`io.fabric8:kubernetes-server-mock`) for Docker-free ordering tests.

- [ ] **Step 1:** Read how `spire-run-worker/build.gradle.kts` imports the Quarkus platform. Note the exact
  platform coordinates.
- [ ] **Step 2:** Confirm that platform manages `io.fabric8:kubernetes-client` and
  `io.fabric8:kubernetes-server-mock` (read the published `quarkus-bom` pom for 3.38.3). Write both resolved
  versions into the PR description. If the mock server is not managed, pin it to the client's version.

---

### Task 1: The module, its tier, the CI jobs and the architecture tests

**Files:**
- Create: `spire-runtime-k8s/build.gradle.kts`, `spire-runtime-k8s/LICENSE` (copy `spire-runtime-docker/LICENSE`, rename the module in line 1)
- Create: `spire-runtime-k8s/src/test/java/dev/codespire/runtime/k8s/ClusterEnforcesPolicyIT.java`, `ClusterIsOpenIT.java`, `KindCluster.java`
- Create: `spire-runtime-k8s/src/test/docker/unit/Dockerfile` (image `spire-test-unit:TEST`: alpine, uid 1001, busybox `nc` and `wget`)
- Create: `spire-arch/src/test/java/dev/codespire/arch/HarnessAndRuntimeModulesAreSeparateTest.java`
- Modify: `settings.gradle.kts`, `LICENSING.md`, `Dockerfile` (COPY line), `build.gradle.kts` (tier), `.github/workflows/ci.yml`
- Modify: `spire-arch/.../TestTierCoverageTest.java` (`ALL_TIERS` gains `kubernetesTestModules`), `CoreIsProviderNeutralTest.java` (scan the new module and `deploy/helm/spire/templates`)

**Interfaces:**
- Produces: root task `testKubernetes`; Gradle tasks `:spire-runtime-k8s:test` (excludes JUnit tag
  `open-cluster`) and `:spire-runtime-k8s:openClusterTest` (only that tag).
- Produces: `KindCluster` test fixture: `static KubernetesClient client()`, `static String newNamespace(String prefix)`
  (creates it with `default-deny`), `static String canaryTarget()` (a listening pod in namespace
  `TEST-canary`, returned as `ip:port`), `static String dnsAddress()` (the `kube-dns` Service's ClusterIP + `:53`).

- [ ] **Step 1: Build file.** `java` plugin, toolchain 25, `implementation(project(":spire-runtime"))`,
  `implementation(platform(<the Quarkus platform from Task 0>))`, `implementation("io.fabric8:kubernetes-client")`,
  `testImplementation(testFixtures(project(":spire-runtime")))`, `testImplementation("io.fabric8:kubernetes-server-mock")`,
  JUnit as in `spire-runtime-docker/build.gradle.kts`. `tasks.test { useJUnitPlatform { excludeTags("open-cluster") } }`;
  register `openClusterTest` of type `Test` with `includeTags("open-cluster")`. Full exception logging as the
  Docker arm's build file has.
- [ ] **Step 2: Tier.** In the root `build.gradle.kts` add `val kubernetesTestModules = listOf("spire-runtime-k8s")`
  and `tasks.register("testKubernetes")` depending on each module's `test`, shaped like `testServices`.
- [ ] **Step 3: Write the architecture test.**

```java
class HarnessAndRuntimeModulesAreSeparateTest {
    @Test void noHarnessModuleDependsOnAnotherHarnessOrOnARuntime() { /* every spire-harness-* build file:
        project(":spire-harness-…") other than ":spire-harness" -> problem; project(":spire-runtime…") -> problem */ }
    @Test void noRuntimeArmDependsOnOrImportsAHarness() { /* spire-runtime-* build files and main sources:
        project(":spire-harness…") or "import dev.codespire.harness." -> problem */ }
    @Test void onlyTheRunWorkerDependsOnBothFamilies() { /* modules with both: exactly ["spire-run-worker"] */ }
    @Test void theRulesBiteOnAForbiddenDependency() { /* feed the checker a build text with
        implementation(project(":spire-harness-codex")) under the name spire-runtime-k8s; expect one problem */ }
}
```

  Read build files as text, as `TestTierCoverageTest` does (`RootBuild.read`, `RootBuild.repoRoot`). Put the
  check in one static method that takes (module name, build text, source imports) so the last test can feed it.
- [ ] **Step 4: The cluster smoke tests.** They exist so no later result is vacuous.

```java
@Tag("kind") class ClusterEnforcesPolicyIT {
    @Test void aDeniedPodCannotReachTheCanaryAndAnAllowedPodCan() {
        // namespace with default-deny; pod "TEST-denied" runs: nc -w 2 <canary>  -> exit non-zero within 60 s
        // pod "TEST-allowed" plus a policy allowing egress to the canary pod -> exit 0
    }
}
@Tag("open-cluster") class ClusterIsOpenIT {
    @Test void aDeniedPodStillReachesTheCanary() { /* same denied pod -> exit 0: this cluster enforces nothing */ }
}
```

- [ ] **Step 5: CI.** In `.github/workflows/ci.yml` add two jobs, shaped like `services`:
  - `kubernetes` ("kubernetes arm"): checkout, JDK 25, `./gradlew :spire-publisher:installDist :spire-model-proxy:installDist`,
    `docker build` for `spire-publisher:TEST`, `spire-model-proxy:TEST`, `spire-egress-proxy:TEST`
    (`deploy/egress`), `spire-test-unit:TEST`; `helm/kind-action` at the SHA `manifests.yml` already pins, with
    a kind config that sets `networking.disableDefaultCNI: true`; apply the Calico manifest at a pinned
    release tag and wait for nodes `Ready`; `kind load docker-image` for the four images; then
    `./gradlew testKubernetes --no-daemon --console=plain`.
  - `kubernetes-open` ("kubernetes arm, no policy"): the same, but the flannel manifest at a pinned tag in
    place of Calico, and `./gradlew :spire-runtime-k8s:openClusterTest --no-daemon --console=plain`.
  Pin both manifests by release tag and write the tags in a comment beside them.
- [ ] **Step 6: Commit, push, open the pull request** (title "Run harness reviews on Kubernetes: slice 1",
  body: goal, the spec path, `Part of #391`). Run `request-review`.
- [ ] **Step 7: Read CI.** `gh pr checks <n> --watch`. Expected: `fast tests` green (the new arch test and
  the tier test pass), `kubernetes arm` green, `kubernetes arm, no policy` green.
- [ ] **Step 8: Mutation-verify.** Add `implementation(project(":spire-harness"))` to
  `spire-runtime-k8s/build.gradle.kts`, push. Expected: only `noRuntimeArmDependsOnOrImportsAHarness` fails.
  Revert, push.

---

### Task 2: SPI additions in `spire-runtime`

**Files:**
- Modify: `spire-runtime/src/main/java/dev/codespire/runtime/RunUnitSpec.java`, `UnitDeployment.java`, `RunRuntime.java`, `UnitInputs.java`
- Move: `spire-runtime-docker/.../TinyproxyConfig.java` → `spire-runtime/src/main/java/dev/codespire/runtime/TinyproxyConfig.java`; move `TinyproxyConfigTest.java` with it
- Modify: callers in `spire-runtime-docker` (`EgressProxyContainer.java`, `DockerRunNetworks.java`)
- Test: `spire-runtime/src/test/java/dev/codespire/runtime/RunUnitSpecFetchTest.java`

**Interfaces — produces:**

```java
// RunUnitSpec: a 13th component, last
public record RunUnitSpec(String runId, ContainerSpec init, ContainerSpec agent, ContainerSpec publisher,
        EnterpriseEnvironment enterprise, EgressPolicy egress, ContainerSpec modelProxy,
        long memoryBytes, long nanoCpus, long diskBytes, Duration wallClock,
        UnitInputs inputs, Set<String> fetchHosts)
public static final String FETCH_PROXY_ALIAS = "spire-fetch";
public static final int FETCH_PROXY_PORT = 8889;
public static final int FETCH_CLOSE_PORT = 8890;
public static final String FETCH_PROXY_ADDRESS = "http://spire-fetch:8889";
public RunUnitSpec withFetchHosts(Set<String> hosts);          // beside withInputs / withModelProxy
public Map<String, String> environmentBehindFetch(ContainerSpec init);
// RunRuntime
default Optional<String> buildLaneRefusal() { return Optional.empty(); }
// TinyproxyConfig (now public)
public record Listener(int port, String allow) {}              // allow == null: any client
public static String configFor(EgressPolicy egress, EnterpriseEnvironment enterprise, Listener listener);
public static String configFor(EgressPolicy egress, EnterpriseEnvironment enterprise, String subnet); // unchanged result
public static String filterFor(EgressPolicy egress);
```

- [ ] **Step 1: Write the failing tests.**

```java
class RunUnitSpecFetchTest {
    @Test void aSpecBuiltBeforeTheComponentExistedHasNoFetchHosts() { assertEquals(Set.of(), unit().fetchHosts()); }
    @Test void withFetchHostsKeepsEveryOtherComponent() {
        RunUnitSpec with = proxiedReviewUnit().withFetchHosts(Set.of("TEST-forge.example"));
        assertEquals(proxiedReviewUnit().inputs(), with.inputs());
        assertEquals(proxiedReviewUnit().modelProxy(), with.modelProxy());
        assertEquals(Set.of("test-forge.example"), with.fetchHosts());   // bare, lower case, as EgressPolicy hosts
    }
    @Test void aFetchHostThatIsNotABareHostNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> unit().withFetchHosts(Set.of("https://TEST-forge.example/x")));
    }
    @Test void initBehindTheFetchProxyGetsTheProxyAndTheDeploymentsTrustOnly() {
        Map<String, String> env = unit().environmentBehindFetch(unit().init());
        assertEquals("http://spire-fetch:8889", env.get("HTTPS_PROXY"));
        assertEquals("http://spire-fetch:8889", env.get("https_proxy"));
        assertEquals("", env.get("NO_PROXY"));
        assertEquals("TEST-token", env.get("SPIRE_CLONE_SECRET"));       // init's own variables survive
    }
    @Test void theDefaultRuntimeRefusesNoBuildLane() { /* an anonymous RunRuntime -> Optional.empty() */ }
}
```

  In the moved `TinyproxyConfigTest` add: `configFor(policy, NONE, new Listener(8889, null))` contains
  `Port 8889` and no `Allow` line; `new Listener(8888, "127.0.0.1")` contains `Allow 127.0.0.1`; the old
  three-argument form returns the same text as before for the Docker arm's subnet.
- [ ] **Step 2: Push; read CI.** Expected: `fast tests` red, compile errors on the missing members.
- [ ] **Step 3: Implement.** `fetchHosts` null reads as `Set.of()` (a stored held-build unit has none).
  Validate hosts with the rule `EgressPolicy.bareHost` uses. `environmentBehindFetch` goes through
  `UnitDeployment` with a new `Placement.BEHIND_FETCH`: the deployment's trust environment, the four proxy
  variables set to `FETCH_PROXY_ADDRESS`, empty `NO_PROXY`/`no_proxy`, then the container's own variables;
  the collision refusal applies as for the other placements. Every existing canonical-constructor call gains
  `null` for the new component; `withInputs` and `withModelProxy` must carry `fetchHosts` through.
  `UnitInputs` javadoc: "an arm delivers the files into the volume before the agent starts" in place of the
  tar wording; `requireReachOnlyInitAndAgent` is unchanged.
- [ ] **Step 4: Push; read CI.** Expected: `fast tests` and `service tests + packaging` green (the Docker
  arm's own tests still pass with the moved class).
- [ ] **Step 5: Mutation-verify** `withFetchHostsKeepsEveryOtherComponent`: drop `inputs` in the wither;
  exactly that test fails. Restore.
- [ ] **Step 6: Commit** ("Add fetch hosts and a fetch placement to the run unit spec").

---

### Task 3: Publisher commands: seal, fetch-closed, stay until TERM

**Files:**
- Create: `spire-publisher/src/main/java/dev/codespire/publisher/Seal.java`, `SealMain.java`, `FetchClosed.java`, `FetchClosedMain.java`
- Create: `spire-publisher/docker/spire-seal.sh`, `spire-publisher/docker/spire-fetch-closed.sh` (shaped like `spire-publish-none.sh`)
- Modify: `spire-publisher/Dockerfile` (two `COPY --chmod=755` lines), `ReadOnlyPublisher.java`, `ReadOnlyPublisherMain.java`
- Test: `spire-publisher/src/test/java/dev/codespire/publisher/SealTest.java`, `FetchClosedTest.java`, `ReadOnlyPublisherStayTest.java`

**Interfaces — produces (the arm passes these arguments in Task 7):**

```
spire-seal --canary <host:port>... [--control <host:port>] --rounds 5 --timeout-ms 500 --interval-ms 250 --deadline-s 60
    exit 0: sealed.  exit 3: not sealed; one stdout line {"sealed":false,"open":"<target>"} or
    {"sealed":false,"control":"<target>"} when the control never answered.
spire-fetch-closed --close <host:port> --target <host:port> --refusals 3 --deadline-s 30
    connects once to --close (a refused connect is fine: already closed), then needs 3 failed connects in a
    row to --target.  exit 0: closed.  exit 4: one stdout line {"fetchClosed":false}.
spire-publish-none --stay-until-term
    exit 0 after TERM when the artifact was printed whole; exit 75 after TERM with no DONE.
```

```java
final class Seal {
    record Target(String host, int port) { static Target parse(String hostPort); }
    record Settings(List<Target> canaries, Target control, int rounds, Duration timeout, Duration interval, Duration deadline) {}
    sealed interface Result { record Sealed() implements Result {} record Open(Target target) implements Result {}
                              record NoControl(Target target) implements Result {} }
    Seal(Settings settings, Connector connector, Clock clock, Sleeper sleeper);
    Result run() throws InterruptedException;
    interface Connector { boolean connects(Target target, Duration timeout); }   // true only on a completed connect
}
final class ReadOnlyPublisher { static final int TERM_BEFORE_DONE = 75; ReadOnlyPublisher stayUntilTerm(CountDownLatch term); }
```

- [ ] **Step 1: Write the failing tests** (fake `Connector`, fake clock; no sockets except one loopback test).

```java
class SealTest {
    @Test void fiveBlockedRoundsInARowWithTheControlAnsweringSeal() { /* canary false x5, control true -> Sealed */ }
    @Test void anOpenRoundRestartsTheCount() { /* blocked x4, open, blocked x5 -> Sealed after 10 rounds */ }
    @Test void aCanaryStillOpenAtTheDeadlineIsNamed() { /* always true -> Open(TEST-canary:1), after 60 s of fake time */ }
    @Test void aControlThatNeverAnswersIsNotASeal() { /* canaries blocked, control false -> NoControl */ }
    @Test void aRefusedConnectIsBlockedAndOnlyACompletedConnectIsOpen() { /* real loopback: closed port -> false; ServerSocket -> true */ }
    @Test void aSealWithNoCanaryIsRefused() { assertThrows(IllegalArgumentException.class, ...); }
}
class FetchClosedTest {
    @Test void itSendsTheCloseOnceThenNeedsThreeRefusalsInARow() {}
    @Test void aProxyThatIgnoresTheCloseFailsAtTheDeadline() { /* target always connects -> exit 4 */ }
    @Test void aCloseTargetThatAlreadyRefusesIsFine() {}
}
class ReadOnlyPublisherStayTest {
    @Test void itPrintsTheArtifactOnceThenWaitsForTerm() { /* DONE present; run() does not return until the latch; one artifact */ }
    @Test void termRightAfterDoneStillPrintsTheWholeArtifact() { /* write DONE, count down at once -> whole artifact, 0 */ }
    @Test void termWithNoDoneExitsSeventyFive() { assertEquals(75, ...); }
    @Test void withoutTheFlagItReturnsAtDoneAsBefore() {}
}
```

- [ ] **Step 2: Push; read CI.** Expected: `fast tests` red, classes missing.
- [ ] **Step 3: Implement.** `SealMain` and `FetchClosedMain` parse arguments, refuse an unknown one with
  exit 2, and print exactly one JSON line on failure. In stay mode `ReadOnlyPublisherMain` installs a JVM
  shutdown hook that counts the latch down and then waits for `run()` to finish; `run()` on the latch does one
  last poll for `DONE`, prints if it had not, and returns 0 or 75. The Docker mode's code path is untouched.
- [ ] **Step 4: Push; read CI.** Expected: `fast tests` green.
- [ ] **Step 5: Mutation-verify** three guards, one push each: the "restart the count" reset in `Seal`
  (`anOpenRoundRestartsTheCount`), the control check (`aControlThatNeverAnswersIsNotASeal`), the last poll on
  `TERM` (`termRightAfterDoneStillPrintsTheWholeArtifact`).
- [ ] **Step 6: Commit** ("Add seal, fetch-closed and a stay mode to the publisher").

---

### Task 4: The closing fetch proxy in the egress image

**Files:**
- Create: `deploy/egress/spire-fetch-proxy.sh`
- Modify: `deploy/egress/Dockerfile` (`COPY --chmod=755 spire-fetch-proxy.sh /usr/local/bin/spire-fetch-proxy`)
- Test: `spire-runtime-k8s/src/test/java/dev/codespire/runtime/k8s/FetchProxyIT.java` (tag `kind`)

**Interfaces — produces:** `spire-fetch-proxy <close-port> <deadline-seconds>`. It writes the tinyproxy files
from `SPIRE_EGRESS_CONF` and `SPIRE_EGRESS_FILTER` exactly as the image's `CMD` does, starts tinyproxy, waits
for one TCP connection on `<close-port>` or for the deadline, stops tinyproxy and exits 0.

- [ ] **Step 1: Write the failing test.**

```java
@Tag("kind") class FetchProxyIT {
    @Test void itForwardsToTheAllowedHostAndStopsForGoodOnClose() {
        // pod A: spire-egress-proxy:TEST, command spire-fetch-proxy 8890 900, config from
        //   TinyproxyConfig.configFor(ENFORCE policy for the TEST origin host, NONE, new Listener(8889, null))
        // pod B (allowed to A): wget through A to the allowed host -> exit 0; to another host -> non-zero
        // pod B: nc A 8890; then nc A 8889 fails; A's container state is terminated, exit 0, restartCount 0
    }
    @Test void itStopsByItselfAtTheDeadline() { /* spire-fetch-proxy 8890 5 -> terminated within 30 s */ }
}
```

- [ ] **Step 2: Push; read CI.** Expected: `kubernetes arm` red (command not found).
- [ ] **Step 3: Implement** the script in POSIX `sh` with busybox `nc -l -p` and `timeout`. No bash.
- [ ] **Step 4: Push; read CI.** Expected: `kubernetes arm` green; `ApkUpgradeIsNotCachedTest` still green.
- [ ] **Step 5: Commit** ("Add a fetch proxy that closes to the egress image").

---

### Task 5: Config, names and refusals (pure)

**Files:**
- Create: `spire-runtime-k8s/src/main/java/dev/codespire/runtime/k8s/KubernetesRuntimeConfig.java`, `UnitNames.java`, `UnitRefusals.java`
- Test: `spire-runtime-k8s/src/test/java/dev/codespire/runtime/k8s/UnitNamesTest.java`, `UnitRefusalsTest.java`, `TestUnits.java` (fixture)

**Interfaces — produces:**

```java
public record KubernetesRuntimeConfig(String namespace, String publisherImage, String egressImage,
        List<String> sealCanaries, String apiCanary, String dnsAddress,
        String dnsNamespace, Map<String, String> dnsLabels,
        List<String> extraAllowedCidrs, List<String> extraDeniedCidrs,
        int sealRounds, Duration sealTimeout, Duration sealDeadline,
        Duration unitStartTimeout, Duration initTimeout,
        String agentRequestCpu, String agentRequestMemory) {}
// refuses: blank namespace, blank images, empty sealCanaries, blank dnsAddress

final class UnitNames {
    static final String UNIT_LABEL = "dev.codespire/unit";       // value: the digest
    static final String ROLE_LABEL = "dev.codespire/role";       // anchor | unit | proxy
    static final String RUN_ID_ANNOTATION = "dev.codespire/run-id";
    static String digest(String runId);                          // 16 lower-case hex of SHA-256(runId, UTF-8)
    static String anchor(String runId);                          // "unit-<d>"
    static String unitPod(String runId);                         // "unit-<d>-unit"   (also its policy's name)
    static String proxyPod(String runId);                        // "unit-<d>-proxy"  (also its policy's name)
    static String environmentSecret(String runId, String role);  // "unit-<d>-env-<role>"
    static String inputsSecret(String runId);                    // "unit-<d>-inputs"
    static List<String> secretNames(String runId);               // every Secret a unit can have, fixed order
    static Map<String, String> labels(String runId, String role);
}
final class UnitRefusals {
    static final long MAX_SECRET_BYTES = 768 * 1024;
    static Optional<String> of(RunUnitSpec spec);                // the detail, or empty
}
// TestUnits: static RunUnitSpec proxiedReview(String runId); static RunUnitSpec withoutModelProxy(String runId); ...
```

Environment-Secret roles, fixed: `fetch`, `agent`, `publisher`, `model`, `egress`, `fetchproxy`.

- [ ] **Step 1: Write the failing tests.**

```java
class UnitNamesTest {
    static final String RUN = "run::github:TEST-owner/TEST-repo:review-1-abcdef123456-0a1b2c3d:1";
    @Test void everyNameIsALegalObjectNameOfAtMostSixtyThreeCharacters() { /* [a-z0-9]([-a-z0-9]*[a-z0-9])?, RUN and a 200-char id */ }
    @Test void theDigestIsSixteenHexCharactersAndStable() {}
    @Test void runIdsThatDifferInOneCharacterGetDifferentNames() { /* ...:1 vs ...:2; TEST-repo vs TEST-Repo */ }
    @Test void theLabelValueIsTheDigestNeverTheRunId() { assertEquals(UnitNames.digest(RUN), UnitNames.labels(RUN, "unit").get("dev.codespire/unit")); }
    @Test void secretNamesListsEveryEnvironmentRoleAndTheInputs() { assertEquals(7, UnitNames.secretNames(RUN).size()); }
}
class UnitRefusalsTest {
    @Test void aProxiedReviewUnitIsNotRefused() { assertEquals(Optional.empty(), UnitRefusals.of(TestUnits.proxiedReview(RUN))); }
    @Test void aUnitWithNoModelProxyIsRefused() { assertEquals(Optional.of("kubernetes_requires_credential_proxy"), ...); }
    @Test void aUnitWithEgressOffIsRefused() { /* same detail */ }
    @Test void aHostMountIsRefused() { /* "kubernetes_has_no_host_mounts" */ }
    @Test void aMountOfAnotherRunsVolumeIsRefused() { /* "kubernetes_reviews_only" */ }
    @Test void aUnitWithNoInputsIsRefused() { /* inputs == null: not a review unit -> "kubernetes_reviews_only" */ }
    @Test void inputsOverTheBoundAreRefused() { /* 768 KiB + 1 -> "inputs_too_large_for_kubernetes" */ }
    @Test void inputsOfTwoHundredTwentyFourKibibytesAreNotRefused() {}
    @Test void anEnvironmentOverTheBoundIsRefused() { /* agent env total 768 KiB + 1 -> "environment_too_large_for_kubernetes" */ }
    @Test void aUnitWithNoFetchHostIsRefused() { /* "kubernetes_requires_fetch_host" */ }
}
```

- [ ] **Step 2: Push; read CI.** Expected: `kubernetes arm` red, classes missing.
- [ ] **Step 3: Implement.** Sizes are UTF-8 bytes of keys plus values. Order of checks in `UnitRefusals.of`
  is the order of the tests above.
- [ ] **Step 4: Push; read CI.** Expected green.
- [ ] **Step 5: Mutation-verify** each refusal by deleting its check: one push per check, or one push that
  deletes all and lists the seven failing tests one-to-one in the PR description.
- [ ] **Step 6: Commit** ("Name a Kubernetes unit and refuse what the arm cannot run").

---

### Task 6: Secrets and policies (pure)

**Files:**
- Create: `spire-runtime-k8s/src/main/java/dev/codespire/runtime/k8s/UnitSecrets.java`, `UnitPolicies.java`
- Test: `UnitSecretsTest.java`, `UnitPoliciesTest.java`

**Interfaces — produces:**

```java
final class UnitSecrets {
    static List<Secret> of(RunUnitSpec spec, KubernetesRuntimeConfig config, OwnerReference anchor);
    // env-fetch = spec.environmentBehindFetch(spec.init()); env-agent = spec.environmentFor(spec.agent());
    // env-publisher = spec.environmentFor(spec.publisher()); env-model = spec.environmentFor(spec.modelProxy());
    // env-egress / env-fetchproxy = SPIRE_EGRESS_CONF + SPIRE_EGRESS_FILTER from TinyproxyConfig; inputs = the files
}
final class UnitPolicies {
    static final List<String> PRIVATE_RANGES = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "100.64.0.0/10", "127.0.0.0/8");
    static NetworkPolicy unit(String runId, OwnerReference anchor);
    static NetworkPolicy proxy(String runId, KubernetesRuntimeConfig config, OwnerReference anchor);
}
```

- [ ] **Step 1: Write the failing tests.**

```java
class UnitSecretsTest {
    @Test void everyContainerGetsItsOwnSecretAndNoValueAppearsTwice() { /* TEST-token only in env-fetch; TEST-model-secret only in env-model */ }
    @Test void theAgentsSecretHoldsThePlaceholderNeverTheRealCredential() {}
    @Test void theInputsSecretHoldsEachFileByteForByte() { /* 224 KiB of TEST- text round-trips */ }
    @Test void aReviewWithNoInputFilesStillGetsAnEmptyInputsSecret() {}
    @Test void theEgressProxyAcceptsLoopbackOnlyAndTheFetchProxyAllowsTheForgeHostAlone() {
        // env-egress conf contains "Port 8888" and "Allow 127.0.0.1"; env-fetchproxy conf "Port 8889", no Allow,
        // filter == filterFor(new EgressPolicy(ENFORCE, spec.fetchHosts())); env-egress filter == filterFor of the
        // spec's own hosts in ENFORCE, whatever the spec's mode (a proxied unit always enforces)
    }
    @Test void everySecretIsOwnedByTheAnchorAndCarriesTheUnitLabel() {}
    @Test void namesComeFromUnitNames() { /* the produced names are a subset of UnitNames.secretNames(runId) */ }
}
class UnitPoliciesTest {
    @Test void theUnitPodMayOnlyOpenTcpToItsOwnProxyPod() {
        // podSelector unit=<d>,role=unit; policyTypes [Ingress, Egress]; no ingress rule; exactly one egress rule:
        // to podSelector unit=<d>,role=proxy; ports TCP 8080, 8889, 8890. No ipBlock, no namespaceSelector, no port 53.
    }
    @Test void theProxyPodTakesIngressOnlyFromItsOwnUnitPod() { /* from unit=<d>,role=unit; TCP 8080, 8889, 8890 */ }
    @Test void theProxyPodReachesDnsAndPublicAddressesOnly() {
        // egress 1: namespaceSelector kubernetes.io/metadata.name=<dnsNamespace> + podSelector dnsLabels; UDP 53, TCP 53
        // egress 2: ipBlock 0.0.0.0/0 except PRIVATE_RANGES + extraDeniedCidrs; TCP 443, TCP 80
    }
    @Test void anExtraAllowedCidrIsItsOwnRuleOnPortsFourFourThreeAndEighty() {}
    @Test void twoUnitsNeverSelectEachOther() { /* unit(A) egress selector does not match labels(B, "proxy") */ }
}
```

- [ ] **Step 2: Push; read CI.** Expected red.
- [ ] **Step 3: Implement** with fabric8 builders. Secret `type: Opaque`, `immutable: true`.
- [ ] **Step 4: Push; read CI.** Expected green.
- [ ] **Step 5: Mutation-verify:** add port 8888 to the unit policy (`theUnitPodMayOnlyOpenTcpToItsOwnProxyPod`);
  drop the `unit` label from the proxy policy's `from` (`theProxyPodTakesIngressOnlyFromItsOwnUnitPod`); put the
  real credential map in `env-agent` (`theAgentsSecretHoldsThePlaceholderNeverTheRealCredential`).
- [ ] **Step 6: Commit** ("Build a unit's Secrets and network policies").

---

### Task 7: Pod manifests and resources (pure)

**Files:**
- Create: `spire-runtime-k8s/src/main/java/dev/codespire/runtime/k8s/ContainerResources.java`, `ProxyPod.java`, `UnitPod.java`
- Test: `PodManifestRulesTest.java`, `ProxyPodTest.java`, `UnitPodTest.java`

**Interfaces — produces:**

```java
final class ContainerResources {            // the spec's "Resources" table, verbatim
    static ResourceRequirements seal();     // 50m/64Mi   -> 500m/256Mi   (also fetch-closed)
    static ResourceRequirements fetch();    // 250m/256Mi -> 2/1Gi
    static ResourceRequirements publisher();// 50m/128Mi  -> 500m/512Mi
    static ResourceRequirements agent(RunUnitSpec spec, KubernetesRuntimeConfig config); // config requests -> spec limits
    static ResourceRequirements modelProxy();   // 50m/128Mi -> 500m/192Mi
    static ResourceRequirements smallProxy();   // 25m/16Mi  -> 500m/64Mi   (egress-proxy, fetch-proxy)
}
final class ProxyPod { static Pod of(RunUnitSpec spec, KubernetesRuntimeConfig config, OwnerReference anchor); }
final class UnitPod  { static Pod of(RunUnitSpec spec, KubernetesRuntimeConfig config, OwnerReference anchor, String proxyPodIp); }
```

Container names, fixed: proxy pod `seal`, `model-proxy`, `egress-proxy`, `fetch-proxy`; unit pod `seal`,
`fetch`, `fetch-closed`, `publisher`, `agent`.

- [ ] **Step 1: Write the failing tests.**

```java
class PodManifestRulesTest {               // runs over BOTH pods
    @Test void noContainerHasAnEnvEntry() { /* env() is empty for every container and init container */ }
    @Test void everyContainerReadsExactlyItsOwnSecret() { /* fetch -> env-fetch; agent -> env-agent; ...; seal, fetch-closed -> none */ }
    @Test void noContainerLacksRequestsOrLimitsForCpuAndMemory() {}
    @Test void bothPodsAreRestrictedAndHoldNoToken() {
        // restartPolicy Never; automountServiceAccountToken false; enableServiceLinks false; shareProcessNamespace not true;
        // pod securityContext runAsNonRoot true, seccompProfile RuntimeDefault; every container
        // allowPrivilegeEscalation false, capabilities drop [ALL]
    }
    @Test void noImageIsUntaggedOrLatest() { /* TestUnits images are name:TEST; a ":latest" spec image fails the builder */ }
    @Test void everyObjectCarriesTheUnitLabelAndTheAnchorAsOwner() {}
}
class ProxyPodTest {
    @Test void sealRunsFirstWithTheWorkerCanaryAndDnsAsItsControl() {
        // args: --canary <each config.sealCanaries()> --control <config.dnsAddress()> --rounds 5 --timeout-ms 500 --interval-ms 250 --deadline-s 60
        // and NOT config.apiCanary()
    }
    @Test void theFetchProxyClosesOnPortEightEightNineZeroAndAtTheInitTimeout() { /* command [spire-fetch-proxy, 8890, <initTimeout s>] */ }
    @Test void theModelProxyAndEgressProxyHaveTcpStartupProbes() { /* 8080 within 120 s; 8888 within 20 s */ }
    @Test void spireEgressResolvesToLoopback() { /* hostAliases 127.0.0.1 -> [spire-egress] */ }
    @Test void theProxiesHaveAReadOnlyRootAndAnEmptyDirTmp() {}
    @Test void theModelProxyMountsNothingOfTheUnit() {}
    @Test void itsDeadlineIsABackstop() { /* activeDeadlineSeconds = wallClock + initTimeout + 60 + 300 */ }
}
class UnitPodTest {
    @Test void initContainersRunInTheOrderSealFetchFetchClosedPublisher() {}
    @Test void sealProbesTheWorkerCanaryAndTheApiAndNeedsTheModelProxy() {
        // --canary <sealCanaries...> --canary <apiCanary> --control spire-model:8080
    }
    @Test void fetchClosedSendsTheCloseAndChecksThePort() { /* --close spire-fetch:8890 --target spire-fetch:8889 --refusals 3 --deadline-s 30 */ }
    @Test void thePublisherIsANativeSidecarThatStaysUntilTerm() { /* init container, restartPolicy Always, args end with --stay-until-term */ }
    @Test void spireModelAndSpireFetchResolveToTheProxyPod() { /* hostAliases <proxyPodIp> -> [spire-model, spire-fetch] */ }
    @Test void theInputsSecretIsMountedReadOnlyInTheAgentOnly() { /* defaultMode 0444; init's inputs mount is an emptyDir; publisher has no inputs mount */ }
    @Test void sharedVolumesAreOnNodeDiskWithTheUnitsSizeLimit() { /* one emptyDir per Mount.volume(), no medium, sizeLimit = diskBytes */ }
    @Test void theAgentsTmpIsMemoryBackedWithTheUnitsSizeLimit() {}
    @Test void readOnlyMountsStayReadOnly() { /* publisher's /handoff readOnly true, as the spec's Mount says */ }
    @Test void thePodStopsAtInitTimeoutPlusWallClock() { /* activeDeadlineSeconds; terminationGracePeriodSeconds 30 */ }
    @Test void theAgentKeepsItsImageArgvAndEntrypoint() {}
}
```

- [ ] **Step 2: Push; read CI.** Expected red.
- [ ] **Step 3: Implement.** A spec `argv` becomes `args`; a spec `entrypoint` becomes `command`; arm-owned
  containers set `command` to their executable (`spire-seal`, `spire-fetch-closed`, `spire-fetch-proxy`).
  The publisher's `args` are the spec's argv plus `--stay-until-term`. Images of arm-owned containers:
  `config.publisherImage()` and `config.egressImage()`.
- [ ] **Step 4: Push; read CI.** Expected green.
- [ ] **Step 5: Mutation-verify:** write one variable into `env` (`noContainerHasAnEnvEntry`); move `seal`
  after `fetch` (`initContainersRunInTheOrderSealFetchFetchClosedPublisher`); mount inputs in the publisher
  (`theInputsSecretIsMountedReadOnlyInTheAgentOnly`); drop the limits of one container
  (`noContainerLacksRequestsOrLimitsForCpuAndMemory`).
- [ ] **Step 6: Commit** ("Build the proxy pod and the unit pod").

---

### Task 8: `create` and `attach`

**Files:**
- Create: `spire-runtime-k8s/src/main/java/dev/codespire/runtime/k8s/KubernetesRunRuntime.java`, `PodLogs.java`, `UnitStartFailure.java`
- Test: `PodLogsTest.java`, `UnitStartFailureTest.java`, `CreateOrderTest.java` (mock server), `KubernetesCreateIT.java` (tag `kind`)

**Interfaces — produces:**

```java
public final class KubernetesRunRuntime implements RunRuntime {
    public KubernetesRunRuntime(KubernetesClient client, KubernetesRuntimeConfig config, RegistryImageReader images);
    // type() KUBERNETES; capabilities() new RuntimeCapabilities(true, true, false, false, false, true)
    // buildLaneRefusal() Optional.of("kubernetes_reviews_only"); steer(...) throws UnsupportedOperationException
    // drainWindow() Duration.ofSeconds(60)
}
public final class UnitRefused extends RuntimeException { public String detail(); }       // thrown by create
public final class UnitNotStarted extends RuntimeException { public String detail(); public boolean imageProblem(); }
final class PodLogs {
    static final int MAX_LINE_CHARS = 64 * 1024;
    static void follow(InputStream log, Consumer<String> lines);      // whole lines; a longer line is cut once, its rest dropped
}
final class UnitStartFailure {
    static String detail(String headline, Pod pod, List<String> logTail);   // phase, each container's state, exit code, OOMKilled, <=20 lines / 2 KiB
}
```

`RunHandle` is `new RunHandle(spec.runId(), UnitNames.anchor(spec.runId()))`. `RegistryImageReader` is
Task 10's; until then pass a stub in tests.

`create` order (each object owned by the anchor): refuse (`UnitRefusals`) → anchor ConfigMap (labels, run-id
annotation, annotation `dev.codespire/wall-clock-seconds`) → Secrets → both policies → proxy pod → wait until
its `model-proxy` and `egress-proxy` are started (startup probes passed) → read its IP → unit pod → wait until
`agent` is running or the pod ended. Any wait is bounded by `config.unitStartTimeout()` plus, for the unit
pod, `sealDeadline + initTimeout`. An existing anchor is an error ("already exists"); nothing is adopted.

Init failure mapping, by the terminated init container: `seal` exit 3 → `UnitNotStarted`, detail
`network_policy_not_enforced: <the line seal printed>`; `fetch-closed` exit 4 → `fetch_proxy_not_closed`;
`fetch` non-zero → rethrow as the Docker arm does for a failed init (read `DockerRunRuntime.runInit` and
`InitFailureReport` and return the same exception type and text shape, so the worker classifies
`CLONE_FAILED` unchanged). The worker maps `UnitNotStarted` to `RUNTIME_UNAVAILABLE` and `UnitRefused` to
`BAD_COMMAND` in Task 11.

- [ ] **Step 1: Write the failing tests.**

```java
class PodLogsTest {
    @Test void aLineSplitAcrossTwoReadsArrivesWhole() {}
    @Test void aLineOverSixtyFourKibibytesIsCutOnceAndTheNextLineStillArrives() {}
    @Test void aLastLineWithNoNewlineArrivesAtTheEnd() {}
    @Test void carriageReturnsAreNotPartOfALine() {}
}
class UnitStartFailureTest {
    @Test void itNamesPhaseContainerStateExitCodeAndOomKilled() {}
    @Test void theLogTailIsAtMostTwentyLinesAndTwoKibibytes() {}
    @Test void anImagePullReasonIsAnImageProblem() { /* ErrImagePull, ImagePullBackOff, InvalidImageName */ }
}
@EnableKubernetesMockClient(crud = true) class CreateOrderTest {
    @Test void theAnchorExistsBeforeAnySecret() { /* fail the first Secret create -> the anchor is there */ }
    @Test void aRefusedUnitCreatesNothing() { /* withoutModelProxy -> UnitRefused, zero objects */ }
    @Test void aSecondCreateForTheSameRunIdFailsAndAdoptsNothing() {}
    @Test void aProxyPodThatNeverStartsFailsWithinTheStartTimeoutAndLeavesTheUnitDiscoverable() {
        // unitStartTimeout 2 s, pod stays Pending -> UnitNotStarted; the anchor is still listed by label
    }
}
@Tag("kind") class KubernetesCreateIT {
    @Test void aUnitStartsAndItsAgentLineReachesAttach() { /* echoing agent: one TEST- line through attach(AGENT) */ }
    @Test void thePublishersOutcomeReachesAttach() { /* agent writes /handoff/artifact.md then DONE -> publisher's lines through attach(PUBLISHER) */ }
    @Test void anInputFileIsReadableAtInputsInTheAgentOnly() { /* agent cats /inputs/context.md; a 224 KiB file hashes equal */ }
    @Test void noPodSpecHoldsAnEnvValue() { /* read both pods back from the API: every env list empty */ }
    @Test void fromTheAgentOnlyTheModelProxyAnswers() {
        // agent script tries, with nc -w 2: the canary target, the API address, kube-dns:53, a public address:443,
        // spire-egress:8888 (the proxy pod IP), spire-fetch:8889 -> each BLOCKED; spire-model:8080 -> OPEN  (M5-AC-3)
    }
    @Test void aFetchProxyThatIgnoresTheCloseFailsTheRun() { /* egress image stand-in whose fetch proxy never exits -> fetch_proxy_not_closed, no agent container ever started (M5-AC-23) */ }
    @Test void thePublisherIsNeverRestartedAndPrintsTheArtifactOnce() { /* restartCount 0; one artifact in the log (M5-AC-28) */ }
}
```

  Add `OpenClusterIT` (tag `open-cluster`): `create` of `TestUnits.proxiedReview` throws `UnitNotStarted`
  whose detail starts `network_policy_not_enforced`; the unit pod's `agent` container status has never been
  `running` (M5-AC-2).
- [ ] **Step 2: Push; read CI.** Expected: `kubernetes arm` and `kubernetes arm, no policy` red.
- [ ] **Step 3: Implement.** Follow logs with fabric8's `watchLog` on the named container. `attach` blocks
  until the container's log stream ends, as the Docker arm's does.
- [ ] **Step 4: Push; read CI.** Expected: both kubernetes jobs green.
- [ ] **Step 5: Mutation-verify:** create Secrets before the anchor (`theAnchorExistsBeforeAnySecret`); skip
  the refusal (`aRefusedUnitCreatesNothing`); in a test-only config, pass no canary to the unit pod's seal on
  the open cluster and confirm `OpenClusterIT` fails (the seal is the only guard there).
- [ ] **Step 6: Commit** ("Create a review unit on Kubernetes and stream its logs").

---

### Task 9: `salvage`, `cancel`, `destroy`, `discoverUnits`, and the contract

**Files:**
- Modify: `spire-runtime-k8s/src/main/java/dev/codespire/runtime/k8s/KubernetesRunRuntime.java`
- Test: `LifecycleTest.java` (mock server), `KubernetesRunRuntimeIT.java` (tag `kind`, extends `RunRuntimeContract`)

**Interfaces — produces:** the remaining `RunRuntime` methods. `egressOf` and `modelProxyOf` keep the SPI
defaults in slice 1.

- `salvage`: wait up to `drainWindow()` for the unit pod to end. Agent exit code from the pod status →
  `Finalization.salvaged(code, "agent exited <code>")`. Publisher terminated with 75 →
  `Finalization.faulted("the publisher was stopped before the agent wrote DONE")`. Pod reason
  `DeadlineExceeded` → `Finalization.overran(...)`. No pod → `Finalization.vanished(...)`. Deletes nothing.
- `cancel`: delete both pods (grace 5 s) and each name in `UnitNames.secretNames` (404 is success). Sets the
  anchor annotation `dev.codespire/cancelled=true` first; `create` re-reads it before it creates the unit pod
  and stops if set. The anchor stays.
- `destroy`: delete every `UnitNames.secretNames` name; delete the anchor (foreground); list pods,
  NetworkPolicies and ConfigMaps by `dev.codespire/unit=<d>` and delete each; poll until none is listed.
- `discoverUnits`: ConfigMaps with `dev.codespire/role=anchor`; `runId` from the annotation.
- The worker deletes `env-fetch` early through `create`: once the `fetch` container has terminated.

- [ ] **Step 1: Write the failing tests.**

```java
@EnableKubernetesMockClient(crud = true) class LifecycleTest {
    @Test void aUnitLeftHalfMadeIsDiscoveredAndDestroyed() { /* anchor + Secrets, no pods -> discoverUnits lists it; destroy -> nothing left */ }
    @Test void destroyDeletesEverySecretByName() { /* all seven names requested; a 404 on each is not an error */ }
    @Test void destroyTouchesNothingOfAnotherUnit() {}
    @Test void cancelDeletesBothPodsAndTheSecretsAndKeepsTheAnchor() {}
    @Test void cancelTwiceDoesNothingTheSecondTime() {}
    @Test void cancelBeforeTheUnitPodExistsStopsCreateFromMakingIt() {}
    @Test void theFetchSecretIsDeletedOnceFetchHasEnded() {}
    @Test void salvageMapsTheAgentsExitCode() { assertEquals(Finalization.salvaged(3, "agent exited 3"), ...); }
    @Test void aPublisherStoppedBeforeDoneIsFaulted() {}
    @Test void aPodPastItsDeadlineOverran() {}
    @Test void aMissingPodVanished() {}
    @Test void salvageDeletesNothing() {}
}
@Tag("kind") class KubernetesRunRuntimeIT extends RunRuntimeContract {
    // runtime(): the arm on KindCluster, canaries = KindCluster.canaryTarget(), dns = KindCluster.dnsAddress()
    // quietUnit(runId): TestUnits.proxiedReview with an agent that sleeps; echoingUnit(runId, marker): prints marker, exits 0
    @Test void afterDestroyNoObjectCarriesTheUnitLabelAndNoSecretNameResolves() {}
    @Test void cancelEndsBothPodsWithinThirtySeconds() {}
}
```

- [ ] **Step 2: Push; read CI.** Expected: `kubernetes arm` red.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Push; read CI.** Expected green, the whole `RunRuntimeContract` included (M5-AC-1).
- [ ] **Step 5: Mutation-verify:** skip Secret deletion in `cancel`
  (`cancelDeletesBothPodsAndTheSecretsAndKeepsTheAnchor`); make `destroy` delete by the role label only
  (`destroyTouchesNothingOfAnotherUnit`); drop the cancelled re-read
  (`cancelBeforeTheUnitPodExistsStopsCreateFromMakingIt`).
- [ ] **Step 6: Commit** ("Salvage, cancel, destroy and discover Kubernetes units").

---

### Task 10: `describeImage` from the registry

**Files:**
- Create: `spire-runtime-k8s/src/main/java/dev/codespire/runtime/k8s/RegistryImageReader.java`
- Create: `spire-runtime-k8s/src/test/resources/registry/` (recorded responses, see Step 1)
- Test: `RegistryImageReaderTest.java`

**Interfaces — produces:**

```java
public final class RegistryImageReader {
    public RegistryImageReader(HttpClient http, RegistryCredential credentialOrNull);
    public ImageDescription describe(String image);   // labels + "repository@sha256:…" from ONE resolution
}
```

`KubernetesRunRuntime.describeImage` delegates to it. JDK `java.net.http.HttpClient`, no new dependency.
Flow: parse `registry/repository:tag` (the registry rules of `DockerRunRuntime.registryHostOf` and
`canonical`) → `GET /v2/<repo>/manifests/<tag>` with the OCI and Docker manifest and index media types →
on 401, read `WWW-Authenticate`, fetch a token from its `realm` (anonymous, or basic with the credential),
retry → the pin is the `Docker-Content-Digest` of that first answer → for an index pick `linux/amd64`, else
the first manifest → `GET` its config blob → `config.Labels`.

- [ ] **Step 1: Record the responses.** With `curl` against the public Codex agent image on GHCR, save the
  401 challenge, the token reply with the token value replaced by `TEST-token`, the index, the amd64
  manifest and the config blob. These are real registry answers; say so, with the date and the tag, in
  `src/test/resources/registry/README.md`.
- [ ] **Step 2: Write the failing tests** against a JDK `HttpServer` that replays them.

```java
class RegistryImageReaderTest {
    @Test void labelsAndPinComeFromTheRecordedIndex() { /* labels contain dev.codespire.agent.harness; pin == "<repo>@" + the recorded digest header */ }
    @Test void thePinIsTheIndexDigestNotThePlatformManifests() {}
    @Test void aSingleManifestImageWorksToo() {}
    @Test void anAnonymousTokenIsFetchedOnAChallenge() { /* the retry carries "Bearer TEST-token" */ }
    @Test void aCredentialIsSentOnlyToTheTokenRealm() {}
    @Test void aMissingTagThrowsAndNamesTheImageNotTheReply() { /* 404 -> exception message holds the image, never the body */ }
    @Test void anImageWithNoLabelsAnswersAnEmptyMap() {}
    @Test void aTagOfLatestOrNoTagIsRefused() {}
}
```

- [ ] **Step 3: Push; read CI.** Expected red. **Step 4: Implement.** **Step 5: Push; read CI.** Expected green.
- [ ] **Step 6: Mutation-verify:** take the digest from the platform manifest
  (`thePinIsTheIndexDigestNotThePlatformManifests`).
- [ ] **Step 7: Commit** ("Describe an image from the registry on the Kubernetes arm").

---

### Task 11: Wire the run worker

**Files:**
- Modify: `spire-run-worker/build.gradle.kts` (`implementation(project(":spire-runtime-k8s"))`, `implementation("io.quarkus:quarkus-kubernetes-client")`)
- Modify: `spire-run-worker/src/main/resources/application.yml`, `WorkerRuntimes.java`, `WorkerSignInRuntime.java`, `RunDispatcher.java`, `RunUnitBuilder.java`, `RunFailures.java`
- Create: `KubernetesRuntimeSettings.java`, `SealCanaryListener.java`, `RefusingSignInRuntime.java`
- Modify: `deploy/compose.yml`, `deploy/compose.ghcr.yml`, `deploy/.env.example` (set `SPIRE_RUN_RUNTIME=docker`)
- Modify: `spire-arch/.../CoreIsProviderNeutralTest.java` (allowlist `KubernetesRuntimeSettings.java` beside `WorkerRuntimes.java`, as a composition root)
- Test: `WorkerRuntimesTest.java` (extend), `KubernetesRuntimeSettingsTest.java`, `SealCanaryListenerTest.java`, `RunDispatcherTest.java` (extend), `RunUnitBuilderReviewTest.java` (extend), `RunLauncherTest.java` (extend)

**Interfaces — consumes:** `KubernetesRunRuntime`, `KubernetesRuntimeConfig`, `RegistryImageReader`,
`UnitRefused`, `UnitNotStarted`, `RunRuntime.buildLaneRefusal()`, `RunUnitSpec.withFetchHosts`.

**Config keys (new):**

| Property | Variable | Default |
|---|---|---|
| `spire.run.runtime` | `SPIRE_RUN_RUNTIME` | none; `%dev` and `%test` set `docker` |
| `spire.run.k8s.namespace` | `SPIRE_RUN_K8S_NAMESPACE` | none; required for `kubernetes` |
| `spire.run.k8s.own-namespace` | `SPIRE_RUN_POD_NAMESPACE` | none; required for `kubernetes` |
| `spire.run.k8s.pod-ip` | `SPIRE_RUN_POD_IP` | none; required for `kubernetes` |
| `spire.run.k8s.seal.canary-port` | `SPIRE_RUN_K8S_SEAL_CANARY_PORT` | `34084` |
| `spire.run.k8s.seal.canaries` | `SPIRE_RUN_K8S_SEAL_CANARIES` | empty |
| `spire.run.k8s.seal.rounds` / `.timeout` / `.deadline` | `SPIRE_RUN_K8S_SEAL_ROUNDS` / `_TIMEOUT` / `_DEADLINE` | `5` / `500ms` / `60s` |
| `spire.run.k8s.egress.extra-cidrs` / `.denied-cidrs` | `SPIRE_RUN_K8S_EGRESS_EXTRA_CIDRS` / `_DENIED_CIDRS` | empty |
| `spire.run.k8s.dns.namespace` / `.labels` | `SPIRE_RUN_K8S_DNS_NAMESPACE` / `_DNS_LABELS` | `kube-system` / `k8s-app=kube-dns` |
| `spire.run.k8s.unit-start-timeout` | `SPIRE_RUN_K8S_UNIT_START_TIMEOUT` | `300s` |
| `spire.run.k8s.request-cpu` / `.request-memory` | `SPIRE_RUN_K8S_REQUEST_CPU` / `_MEMORY` | `250m` / `1Gi` |

Also set `quarkus.kubernetes-client.devservices.enabled: false`.

- [ ] **Step 1: Write the failing tests.**

```java
// WorkerRuntimesTest
@Test void dockerIsSelectedByName() {}
@Test void kubernetesIsSelectedByNameAndGetsItsConfig() { /* type() == KUBERNETES; config.namespace() == "TEST-runs" */ }
@Test void anUnsetOrUnknownRuntimeRefusesStartupNamingTheVariable() { /* message contains SPIRE_RUN_RUNTIME */ }
@Test void theDockerArmIsNotBuiltWhenKubernetesIsSelected() {}

class KubernetesRuntimeSettingsTest {
    @Test void theRunNamespaceMayNotBeTheWorkersOwn() { /* both "TEST-app" -> IllegalStateException naming SPIRE_RUN_K8S_NAMESPACE (M5-FR-37) */ }
    @Test void theWorkerCanaryIsItsPodIpAndCanaryPort() { assertEquals(List.of("10.0.0.7:34084"), config.sealCanaries()); }
    @Test void theApiCanaryComesFromTheServiceVariables() { /* KUBERNETES_SERVICE_HOST 10.96.0.1, PORT 443 -> "10.96.0.1:443" */ }
    @Test void theDnsAddressIsTheFirstNameserverOfResolvConf() { /* "nameserver 10.96.0.10" -> "10.96.0.10:53" */ }
    @Test void aMissingPodIpRefusesStartup() {}
    @Test void theUnitImagesComeFromTheExistingKeys() { /* spire.run.publisher-image, spire.run.egress.image */ }
}
class SealCanaryListenerTest {
    @Test void itAcceptsAConnectionAndClosesIt() {}
    @Test void itDoesNotListenWhenTheRuntimeIsDocker() {}
}
// RunDispatcherTest
@Test void aBuildLaneCommandOnAnArmThatRefusesItFailsBadCommandBeforeAnyUnit() {
    // fake runtime with buildLaneRefusal() == Optional.of("TEST-refusal"): result is BAD_COMMAND, detail "TEST-refusal",
    // create() never called, the claim is still written (a redelivery must not run it either)
}
// RunLauncherTest
@Test void aUnitTheArmRefusesIsBadCommandWithItsDetail() { /* create throws UnitRefused("TEST-detail") */ }
@Test void aUnitThatDidNotStartIsRuntimeUnavailableWithItsDetail() { /* UnitNotStarted, imageProblem false */ }
// RunUnitBuilderReviewTest
@Test void aReviewUnitNamesItsForgeHostAsTheFetchHost() { /* remote https://TEST-forge.example/TEST-owner/TEST-repo.git -> Set.of("test-forge.example") */ }
```

  `ReviewRunDispatcher` is **not** refused: add `aReviewLaneCommandIsNotRefusedByTheBuildLaneRefusal` to
  `ReviewRunDispatcherTest`.
- [ ] **Step 2: Push; read CI.** Expected: `service tests + packaging` red.
- [ ] **Step 3: Implement.** `WorkerRuntimes` has one `@Produces @Singleton RunRuntime runtime()` that
  switches on `spire.run.runtime`; the fabric8 client comes from `Instance<KubernetesClient>` so the Docker
  path never asks for it. `RefusingSignInRuntime` implements `SignInRuntime`: `type()` is `KUBERNETES`,
  `start` throws `UnsupportedOperationException("subscription sign-in is not available on Kubernetes yet")`,
  `discover` returns an empty list, `find` empty, `destroy` true. `SealCanaryListener` opens a `ServerSocket`
  on `0.0.0.0:<canary-port>` at startup (runtime `kubernetes` only), accepts and closes. The exceptions map in
  the place `RunLauncher` already turns a `create` failure into a cause (`RunFailures`); an `imageProblem` is
  `IMAGE_UNAVAILABLE`.
- [ ] **Step 4: Push; read CI.** Expected: all jobs green; `M0WalkingSkeletonTest` and `DockerRunRuntimeIT`
  pass unchanged (M5-AC-19).
- [ ] **Step 5: Mutation-verify:** remove the namespace compare (`theRunNamespaceMayNotBeTheWorkersOwn`);
  remove the dispatcher refusal (`aBuildLaneCommandOnAnArmThatRefusesItFailsBadCommandBeforeAnyUnit`).
- [ ] **Step 6: Commit** ("Select the run runtime by configuration").

---

### Task 12: The Helm chart

**Files:**
- Create: `deploy/helm/spire/templates/run-worker.yaml`, `run-worker-rbac.yaml`, `run-namespace.yaml`
- Modify: `deploy/helm/spire/values.yaml`, `templates/configmap.yaml`, `templates/_helpers.tpl`, `tests/render.sh`
- Modify (re-rendered, never by hand): `deploy/k8s/*/spire.yaml`. They must not change with the run worker off.
- Modify: `.github/workflows/docker.yml` only if `spire-test-unit` must not be published (it must not).

**Values (new):**

```yaml
runWorker:
  enabled: false
  reviewConcurrency: 2
  sealCanaryPort: 34084
  extraEnv: []
  extraEnvFrom: []
  runs:
    namespace: ""              # required when enabled; refused when equal to the release namespace
    createNamespace: true
    dns: {namespace: kube-system, labels: {k8s-app: kube-dns}}
    egress: {extraCidrs: [], deniedCidrs: []}
    requests: {cpu: 250m, memory: 1Gi}
  images:                      # tags default to the chart's appVersion; "latest" and "" are refused
    publisher: ""
    modelProxy: ""
    egressProxy: ""
factory:
  agentImages: {}              # harness name -> image; rendered as SPIRE_FACTORY_AGENT_IMAGE_<NAME>
```

**What is rendered when enabled:** the run worker Deployment (1 replica, the common environment, its
database, keyset and OIDC references shaped like `worker.yaml`, `SPIRE_RUN_RUNTIME=kubernetes`, the keys of
Task 11, `SPIRE_RUN_POD_IP` and `SPIRE_RUN_POD_NAMESPACE` from the downward API, a container port for the
canary), its Service and ServiceAccount; in the run namespace: the Namespace (label
`pod-security.kubernetes.io/enforce: restricted`), `default-deny`, a Role with exactly the spec's RBAC table,
and a RoleBinding to the run worker's ServiceAccount in the release namespace.

- [ ] **Step 1: Add the assertions to `tests/render.sh`** (a new `check_run_worker`), then their `--self-test`
  entries, which must each fail on a broken copy of the chart:
  - off (default): the render is byte-identical to the render before this task (compare with the committed
    `deploy/k8s/simple/spire.yaml` drift check);
  - on: the Role has no `pods/exec`, no `get|list|watch` on `secrets`, no `patch` on `networkpolicies`, and
    is in the run namespace; there is no ClusterRole and no ClusterRoleBinding;
  - on: `default-deny` selects every pod and allows nothing;
  - on, `runs.namespace` empty: the render fails naming `runWorker.runs.namespace`;
  - on, `runs.namespace` equal to the release namespace: the render fails (M5-AC-35);
  - on, `images.publisher=TEST-registry/spire-publisher:latest`: the render fails (M5-AC-34);
  - on: each unit image ends with the chart's `appVersion`;
  - on: no template text contains a harness name (`grep -i` for the names in `HarnessType`), M5-AC-27.
- [ ] **Step 2: Push; read CI.** Expected: `manifests.yml`'s verify job red.
- [ ] **Step 3: Write the templates.** Follow `worker.yaml` for structure and `spire.securityContext`.
- [ ] **Step 4: Push; read CI.** Expected: verify, `--self-test` and the kind install green; the drift check
  shows no change in `deploy/k8s` (M5-AC-18).
- [ ] **Step 5: Mutation-verify** in `--self-test`: a chart copy with `pods/exec` in the Role, and one with
  `get` on `secrets`, each trip their own assertion.
- [ ] **Step 6: Commit** ("Deploy the run worker from the chart, off by default").

---

### Task 13: Documents

**Files (modify):** `docs/DECISIONS.md`, `docs/factory/RUN-TOPOLOGY.md`, `docs/SECURITY.md`,
`docs/UNVERIFIED.md`, `docs/HISTORY.md`, `docs/factory/MODULES.md`, `docs/factory/ROADMAP.md`,
`docs/factory/PRD.md`, `docs/factory/ARCHITECTURE.md`, `deploy/README.md`, `deploy/.env.example`,
`LICENSING.md` (done in Task 1; check the row's wording), `CLAUDE.md`.

- [ ] **Step 1: `docs/DECISIONS.md`, new ADR-052** "The Kubernetes run unit is two pods". Decision, and why:
  one network namespace per pod; the seal and the 1 s open window measured on kube-router; the closing fetch
  proxy and the two rejected options; proxied units only; per-container Secrets; the anchor; fabric8. Say what
  slice 1 does not do (records, node canary, self-check, sign-in).
- [ ] **Step 2: `docs/factory/RUN-TOPOLOGY.md`:** a new §6B.3 with the two-pod picture from the spec; correct
  §9.7 (shared volumes are on node disk with `sizeLimit`; only the agent's `/tmp` is memory-backed); §6B's
  last line "Only the Docker arm copies them today" gains the Secret volume.
- [ ] **Step 3: `docs/SECURITY.md`:** what the Kubernetes arm removes (the socket) and what it does not: the
  per-run Secrets in etcd (accepted by the operator on 2026-11-28); a node or host can reach a pod whatever
  the policy says; the model proxy trusts what reaches port 8080; no per-unit pid limit; the first second of
  a pod on kube-router and why the seal exists.
- [ ] **Step 4: `docs/UNVERIFIED.md`,** a new entry "Harness reviews on Kubernetes, slice 1": the claim; what
  CI proves (kind + Calico, kind + flannel); what is not proven: any cluster but kind and the homelab scratch
  namespace; log rotation during a long run; ingress timing on the proxy pod; the node canary and the startup
  self-check (slice 2); `egressOf` and `modelProxyOf` return nothing yet; `arm64`; a clone of a large
  repository within the fetch limits. Write only what was read in a CI log or in the live record of Task 14.
- [ ] **Step 5: `docs/HISTORY.md`:** the milestone entry, and "Upgrading": `SPIRE_RUN_RUNTIME` is now
  required (the compose files set `docker`); the publisher and egress proxy images must be the new ones.
- [ ] **Step 6:** `ROADMAP.md` and `PRD.md` (M5 rewritten as the review lane on Kubernetes; builds and a
  second build harness move to a later milestone), `MODULES.md` §8, `ARCHITECTURE.md` §3.2 (the real
  interface), `deploy/README.md` (the "Kubernetes does NOT get the run worker yet" section), `CLAUDE.md`
  Status (rewrite the snapshot; do not append).
- [ ] **Step 7: Push; read CI** (docs-only checks green). **Commit** ("Document the Kubernetes review unit").

---

### Task 14: The live check in a homelab scratch namespace

Not on spired.dev. Steps marked **[OK]** need the operator's word before they run; the rest are reads.

- [ ] **Step 1 [OK]:** Confirm the images of this branch exist on GHCR at `sha-<short>` (run worker,
  publisher, model proxy, egress proxy, Codex agent). If `docker.yml` does not publish for a pull request,
  ask the operator to run it for the branch.
- [ ] **Step 2 [OK]:** Operator creates the Secrets the chart needs in namespace `spired-m5-scratch`, and a
  public route to the scratch gateway for the forge webhook.
- [ ] **Step 3 [OK]:** `helm upgrade --install spire deploy/helm/spire -n spired-m5-scratch --create-namespace
  -f deploy/helm/spire/values-simple.yaml --set image.tag=sha-<short> --set runWorker.enabled=true
  --set runWorker.runs.namespace=spired-m5-scratch-runs --set factory.agentImages.codex=ghcr.io/artyomsv/spire-agent-codex:sha-<short>`
- [ ] **Step 4 [OK]:** Operator registers the test repository and its reviewer account in the scratch
  dashboard, adds an `openai` API key under Settings → Harness credentials, and sets the repository's
  "Review with" to Harness, Codex.
- [ ] **Step 5 (read):** `kubectl auth can-i get secrets -n spired-m5-scratch-runs --as=system:serviceaccount:spired-m5-scratch:<run worker sa>` → `no`;
  the same for `create pods/exec`; `create pods -n spired-m5-scratch` → `no`; `get nodes` → `no` (M5-AC-17).
- [ ] **Step 6 [OK]:** Operator opens a `TEST:` pull request on the test repository.
- [ ] **Step 7 (read, during the run):** `kubectl -n spired-m5-scratch-runs get pods,configmaps,networkpolicies -l dev.codespire/unit`
  → one anchor, two pods, two policies. `kubectl -n spired-m5-scratch-runs get pod <unit pod> -o yaml` and
  the proxy pod: no `env:` value anywhere; only `model-proxy` names `…-env-model`. Never print a Secret
  (M5-AC-5).
- [ ] **Step 8 (read):** `kubectl -n spired-m5-scratch-runs logs <unit pod> -c seal` → sealed, no open
  target. `kubectl … get pod <unit pod> -o jsonpath='{.status.initContainerStatuses[?(@.name=="publisher")].restartCount}'` → `0`.
- [ ] **Step 9 (read):** the pull request has a posted review (M5-AC-4). The run row in the scratch dashboard
  is `succeeded`.
- [ ] **Step 10 [OK]:** Operator closes a second `TEST:` pull request while its review runs.
  `kubectl -n spired-m5-scratch-runs get pods -l dev.codespire/unit -w` → both pods gone within 60 s; the run
  is `CANCELLED` (M5-AC-11).
- [ ] **Step 11 (read):** after both runs: `kubectl -n spired-m5-scratch-runs get pods,configmaps,networkpolicies -l dev.codespire/unit`
  → nothing; `kubectl -n spired-m5-scratch-runs get secret unit-<d>-env-model` → `NotFound`.
- [ ] **Step 12:** Write `docs/factory/M5-SLICE1-ACCEPTANCE.md` in the style of
  `docs/factory/HARNESS-REVIEWS-3-ACCEPTANCE.md`: pull request links, run ids, what each read showed, what was
  not captured. Update the `docs/UNVERIFIED.md` entry. Push; read CI; commit ("Record the slice 1 live check").
- [ ] **Step 13 [OK]:** `helm uninstall spire -n spired-m5-scratch`, then
  `kubectl delete namespace spired-m5-scratch spired-m5-scratch-runs`.

---

## Acceptance map

| Criterion | Task |
|---|---|
| M5-AC-1 contract | 9 |
| M5-AC-2 open cluster | 1, 8 |
| M5-AC-3 isolation (no node) | 8 |
| M5-AC-4 scratch review, AC-5, AC-11, AC-17 live | 14 |
| M5-AC-18 chart on/off, AC-34, AC-35 | 12 |
| M5-AC-19 Docker unchanged | 11 |
| M5-AC-23 fetch proxy not closed | 3, 4, 8 |
| M5-AC-26, AC-27 architecture | 1, 12 |
| M5-AC-28 publisher mode | 3, 8 |
| M5-AC-29 refusals | 5, 11 |
| M5-AC-31 sizes | 5, 8 |
| M5-AC-32 describeImage | 10 (the live read is Task 14, step 9: the run started, so the image check passed) |
| M5-AC-33 resources | 7 |

Not in this slice: `egressOf`, `modelProxyOf`, the node canary, the startup self-check, the quota, sign-in.
