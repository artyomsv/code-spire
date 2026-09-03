# Code Review State: global / m1-task11-corporate-environment

Last reviewed: 2026-09-03
Rounds completed: 1

Covers commit `626b0f6` (Task 11 — the corporate run-unit environment, FR-F14). Four lenses:
security-officer, code-reviewer, rules-compliance, qa. Semgrep 21/21 files, 0 findings.

**The round's headline: the feature reached one container out of three, and three documents said
otherwise.** `SSL_CERT_FILE`, `GIT_SSL_CAINFO` and `NODE_EXTRA_CA_CERTS` cover OpenSSL, the git
binary and Node — the agent's world. The init clone and the publisher are neither: they are a JVM
running JGit, which reads the JDK trust store and `ProxySelector` and holds zero references to any of
those names, measured against the jar. So behind a TLS-inspecting proxy the clone failed at the forge
and the push failed at the forge, which are the two calls most likely to meet a proxy.

**The test could not see it, and that is the transferable part.** It mounted a bundle into all three
containers and read it back with `cat`. Reading a certificate file proves the *bind* and says nothing
about *trust*. The replacement stands up a real TLS server behind a private CA and asserts the
handshake fails before the bundle is applied and succeeds after — mutation-verified, three tests die
without the trust line.

The security lens' diagnosis was verified independently before being acted on (the publisher's base
image, its entrypoint, the JGit dependency, and a grep of the jar), because this project has already
had one confident agent diagnosis turn out to be false.

## Resolved (fixed in code; do not re-raise)

- [security/H1] **The CA bundle and proxy reached the agent only.** `CorporateTransport` builds an
  `SSLContext` from the PEM and a `ProxySelector`/`Authenticator` from the proxy, called by both
  `CloneMain` and `PublisherMain` before anything reads a credential. Certificates are read with the
  PLURAL `generateCertificates` — the singular form reads one block, which is the bug that leaves a
  bundle working for the forge and failing for the model API. `jdk.http.auth.tunneling.disabledSchemes`
  is cleared, because the JDK refuses Basic to a proxy on a CONNECT tunnel by default and that is
  every https-through-a-proxy call a run makes — round 1
- [code-quality/1, qa/L] **A relative bundle path walked through the startup refusal**, resolving
  against the worker's CWD and then reaching the runtime as a VOLUME NAME — an empty volume at the
  bundle path, verbatim the outcome the refusal exists to prevent. Absoluteness is syntax, so it sits
  in `HostMount` beside the check `path` already had; the config stores the resolved form so the file
  checked and the file bound are the same one — round 1
- [security/M1, rules/1, code-quality/4, qa/M5] **Only the FIRST proxy password was scrubbed.** The
  two proxies are separately configurable, and the one dropped was as likely to be the https
  credential every forge and model call uses — round 1
- [security/M3, code-quality/5, qa/M6] **The Basic form used the wrong username.** The proxy password
  was paired with the SCM username, producing `base64(scmUser:proxyPassword)` — a string no request
  carries — while `SecretScrub`'s own javadoc names `Proxy-Authorization` as one of the three forms
  that matter. Each secret now carries its own username, and the value is percent-decoded because the
  URL holds `p%40ss` while the header holds `p@ss` — round 1
- [security/M2, code-quality/6] **`passwordIn` missed the scheme-less form** curl accepts and
  operators write, so the password was set in every container and scrubbed from nothing; and an `@`
  in a path produced a false secret. Bounded to the authority, scheme optional — round 1
- [security/M5] **A bundle holding a PRIVATE KEY was accepted** — the shape of a combined
  `server.pem` — and mounted into the container running untrusted model output. Refused, as is a file
  with no certificate block (a keystore given by mistake, which fails every handshake naming the
  forge) — round 1
- [security/M4, qa/M4] **A proxy password under the scrub's floor was silently unredacted.** Refused
  at startup: below it the password appears verbatim in every failure detail the deployment writes,
  with nothing on screen saying why — round 1
- [qa/H1] **Two guards were proven for one container out of three** — the collision fixture used a
  name only the publisher sets, the shadow fixture a path init also mounts. Both mutations survived.
  One name and one mount per role now; both die — round 1
- [security/L1, code-quality/3] **Docker Hub's three spellings were three registries**, so a fully
  qualified reference got a silent anonymous pull while configuring `docker.io` broke the bare form.
  Both sides of the comparison go through one function — round 1
- [qa/M3] **The pull attachment was untestable and untested** — `authFor` could answer correctly for
  ever while nothing carried its answer. `pullCommandFor` is extracted, with the auth line after both
  branches so "authenticated in one branch only" is inexpressible. Mode R did NOT cover it either, as
  the known-gap note assumed: no step performs a private pull — round 1
- [qa/M1] **`WorkerRuntimes` had zero tests**, and it is the one place the credential is handed to the
  thing that pulls — round 1
- [qa/M2] **The fakes were one line from re-arming the trap this repository has hit six times.** Two
  narrow doubles each left the other accessor null; one plausible future read produced 44 NPEs across
  three suites, all presenting as unrelated launcher faults. One shared fake answers every accessor — round 1
- [rules/2, security/M6, qa/H2] **The build guard missed `ENV NAME value`**, still-valid Dockerfile
  syntax, while its own javadoc claimed to cover it — the pattern that did was used only by the
  vacuity probe. Its credential half was keyword-anchored, so a name on a continued line escaped.
  Both now have positive controls — round 1
- [qa/H3] **The IT's registry case asserted nothing on an empty list** — the vacuity hole this
  repository already paid for once. It asserts the unit's three containers first — round 1
- [code-quality/Q, qa] **Two tests asserted only `isPresent()`**, so a parser returning the whole
  userinfo passed both. They assert the value — round 1
- [security/M7, code-quality/2] The bundle is validated in the worker's filesystem and resolved in the
  runtime's. Documented in both operator-facing places, with what changes when the worker is
  containerised — round 1
- [code-quality/S, security] **The proxy credential is readable by the agent by design**, because
  every container must route through it. Stated plainly next to the registry credential's opposite
  guarantee, so nobody generalises from one to the other — round 1
- [rules/3] `CLAUDE.md` had no M1 bullet for this task, while the four preceding M1 commits each
  added theirs — round 1
- [rules/4, code-quality] `createContainer` was 46 lines against a 30 max; `bindsFor` extracted — round 1
- [code-quality/Q, rules/7] `Optional` used as a method parameter twice, which the rules forbid — round 1
- [code-quality/S] The registry refusal named only the FIRST missing part, costing one worker restart
  per omission — round 1
- [code-quality/S] The collision message named the IMAGE, and init and publisher share one — so it
  could not say which collided. It names the role — round 1
- [code-quality/S] **The collision guard's rationale sold a scenario the code cannot produce.** The
  deployment sets nine fixed names and every container-side name is a literal, so no collision is
  reachable today. The guard is worth having as an SPI invariant before a second arm can reach it;
  the prose now says that instead — round 1
- [code-quality/3] The `DOCKER_HUB` javadoc misstated what docker-java expects — round 1
- [security/L4] Mode R put the registry secret on a command line, where it lands in shell history and
  `ps` — round 1
- [rules/7, code-quality] `.env.example`'s garbled "Set both cases are handled for you" — round 1
- [security/L2] A registry PORT is part of the host, and the docs did not say so — round 1
- [code-quality/S] `HostMount`'s javadoc named Docker in the neutral SPI. Reworded to the neutral
  claim, which is also the true one — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [code-quality/Q] Split `RunUnitSpec`'s validators into a `RunUnitInvariants` helper. Declined for
  the reason `requireContainment`'s own javadoc gives: this is the only place that sees all three
  containers, and the class is 156 lines against a 300 budget. The reviewer independently reached the
  same conclusion.
- [code-quality/Q] Introduce `RunLimits(memoryBytes, nanoCpus, wallClock)` to take the record from 8
  components to 6. A real improvement and a real risk — five construction sites, mid-milestone, on a
  record whose transposition hazard predates this task. Worth doing; not in a review-fix batch.
- [code-quality/5, rules/5] `DockerRunRuntime` is past the 300-line class budget. Pre-existing, and
  this change now removes more from it than it adds. Tracked as debt rather than split here.
- [security/L3] Move the environment-collision check to startup. It is unreachable today (see the
  resolved entry above), so a startup copy would guard nothing while duplicating the invariant in a
  second place — which is how the pool's "selectable" predicate drifted across three.
- [qa/L] `RUN_UNIT_IMAGES` is listed in both the Java guard and the Gradle inputs. Deriving both from
  one directory is the right shape when FR-F15 adds a third run-unit image; two entries that must
  agree are not worth a directory scan today.
- [code-quality/S] `assertSame(EnterpriseEnvironment.NONE, …)` pins identity. It discriminates (it
  killed the early-return mutation) and an equivalent-value refactor reddening is the correct signal.
- [qa/L] A source-scan guard in `spire-arch` for "no arm calls `ContainerSpec.environment()` directly"
  and "every `HostMount` bind is `AccessMode.ro`". A good complement to the 11-minute IT and the right
  shape for this repository; it belongs with the Kubernetes arm that makes it necessary.
