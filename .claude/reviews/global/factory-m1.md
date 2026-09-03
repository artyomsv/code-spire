# Code Review State: global / factory-m1

Last reviewed: 2026-09-03
Rounds completed: 1

The **whole-PR** round over PR #96 (`feat/factory-m1`, 40 commits, 332 files, +42817/−116), after all
thirteen tasks had been reviewed individually. Four lenses. Semgrep 263 files, 4 findings, all in test
sources. 32 findings.

**The round's value was structural, and worth stating for the next one.** Almost nothing here is a
defect inside a task. Everything is one of three shapes a per-task review *cannot* see:

- **a fix that landed on one of two siblings** — Task 3 fixed the swallow on `killQuietly` and left
  three siblings; Task 7 removed one of four spellings of an MDC key;
- **a guard whose input can never satisfy it** — the socket's `-1` from a `SELECT count(*)`; the
  producer guard allowlisting the file that holds the alias map;
- **a claim in module A about the behaviour of module B** — `ChangeKind` argued for by three modules
  and dropped by the fourth; six documents describing a read-only clone token; `SECURITY.md` pointed
  at by five places and containing nothing.

Two per-task reviews each concluded the cancel window was closed. Both were right only for a run
already executing, and neither could see the other's half.

## Resolved (fixed in code; do not re-raise)

- [cr/C1] **`RunCommandDeserializer` did the opposite of its own javadoc.** No `deserialize` override,
  so the base implementation wrapped and threw and the messaging layer failed the channel;
  `failure-strategy` does not apply to a deserialization fault. It served BOTH worker channels, so a
  poison record on `cs.run-control` was a worker that could not be cancelled. Four siblings override;
  this one was a copy that dropped the only line that mattered. Verified by disassembly by the
  reviewer, and by a new per-class test that fails against the shipped class — round 1
- [qa/F2] **The producer guard did not guard the likeliest producer.** It allowlisted
  `RunFailureCause.java` wholesale, and that file holds `ALIASES` — whose javadoc says translation
  "belongs here". Adding an alias left the fast tier green, measured. The exemption is now the
  constant's declaration line; verified passing clean and failing on exactly that mutation — round 1
- [qa/F1] **Nothing asserted any sandbox control.** `withCapDrop`, `no-new-privileges`,
  `withPidsLimit`, `withMemory`, `withNetworkMode` — zero references across every test source. The
  daemon-driving IT asserts behaviours and never inspects a `HostConfig`. `hostConfigFor` extracted;
  `SandboxControlsTest` asserts all three containers; each control mutation-verified — round 1
- [cr/I3, sec/L1] **The socket's "no such run" guard was dead** — it tested for `-1` from a
  `SELECT count(*)`, which always returns a row. Recorded as resolved by a prior round and inert. It
  asks `FactoryRunProjection.find` now, as the REST route does; `countFor` and `emptyPage` deleted — round 1
- [cr/I4] **A broker blip burned `REAP_SLOT` for ever.** The claim is taken before the publish, which
  is right — a duplicate report lands a second unpriceable charge line — but there was no release, so
  seconds of unavailability left the run with no automated path to a terminal result. Released on
  publish failure; `RunResultReporter.report` answers instead of returning `void`; mutation-verified — round 1
- [qa/F3] **The seventh fake-coverage trap, and the first SILENT one.** Every previous instance failed
  loudly; this one sits under the sweep's own `catch (RuntimeException)`, so a plausible call to
  `staleLeases` left all 27 tests green with the feature inert. The fake throws from every method it
  does not answer — round 1
- [sec/M3] **`SECURITY.md` had no factory section**, while five places pointed at it including a
  javadoc reading "stated in SECURITY.md rather than mitigated away". Written, including what is NOT
  mitigated: the root-equivalent socket, unrestricted egress on the Docker arm against a PRD that says
  it defaults to deny, no disk bound, and unverifiable self-reported usage — round 1
- [sec/M1] **One SCM token serves the clone and the push**, while `RUN-TOPOLOGY` says in bold "Never
  the same secret in both" and five other places agree. Corrected in all six rather than invented: a
  read scope is forge-specific and a product decision. The isolation that DOES hold — the agent gets
  no git credential at all — is now stated too. Added to `UNVERIFIED.md` §E — round 1
- [rules/1, rules/2] **`EXECUTION-LAYER.md` and `ROADMAP.md` still showed both pool transitions as
  automatic** — the commit that corrected this claim in `PRD.md` and `ARCHITECTURE.md` did not grep
  for it — round 1
- [rules/3, cr/I8] **`CLAUDE.md`'s M1 bullet omitted 8 of 13 tasks**, each deferred by its own round to
  "the milestone documentation pass", and every count in the file was M0's — round 1
- [rules/4] **`README.md`'s licence rows omitted eight modules**, two of them FSL deployables, which
  reads as permissive by omission; the services table omitted the run worker — round 1
- [rules/5] **Neither Keycloak realm defined the run worker's OIDC client** while four documents told
  an operator it did. Adding it caught a second defect: the copied client inherited the review
  worker's `/wk` prefix and port, and the run worker owns `/rw` on 34083 — round 1
- [rules/6] `MODULES.md` omitted `spire-agent-image` while its own README calls it "every new module";
  the factory reading table omitted the contract — round 1
- [sec/H2, cr] **`UNVERIFIED.md` claimed overshoot is bounded by in-flight runs.** It is bounded by
  queued + in-flight, and nothing bounds queued. The register written to catch untested claims shipped
  with an untested claim — round 1
- [cr] The register also listed the dead socket guard under "untested". It was not untested, it could
  not work — a distinction the page exists to keep — round 1
- [rules/7] The duplicated `LABEL` block in the codex Dockerfile, from a patch script that ran twice — round 1
- [rules/8] No `.dockerignore` for the agent build context — round 1
- [rules/9, cr/S6] `RUN_ID_MDC` declared twice in one module — round 1
- [cr/S1] `RunResultSaga`'s poison comment named a DLQ an acked record never reaches — round 1
- [rules/near-miss, qa/F4] `testServices`' own description said "four deployables plus
  spire-runtime-docker" with six modules in the list. Task 0 fixed this drift; Task 12 re-broke it — round 1
- [cr/S8] An orphaned javadoc in `OrphanWatchdogTest` — the tracked trap, again — round 1

## Filed as debt (real, and not a review-fix)

- [cr/I6] The runtime SPI has no conformance contract, while the harness SPI ships one a second arm
  extends. Three independent `FakeRuntime` classes stand in — `techdebt/spire-runtime/3-3-…`
- [cr/I5] `RuntimeCapabilities` is entirely unread in production and its javadoc says the domain reads
  it. `nativeSidecar` is documented as the K8s differentiator and the sentinel path is unconditional —
  `techdebt/spire-runtime/3-2-…`
- [sec/M2] No disk bound on a run unit; one `fallocate` takes the host and every other run with it —
  `techdebt/spire-runtime-docker/2-2-…`
- [cr/S3] Two credential scrubbers with divergent rules, the weaker one in the container holding the
  git write token — `techdebt/global/3-2-two-credential-scrubbers-…`
- [cr/I7] `ChangeKind` carried across the wire specifically to be reported, dropped at the worker —
  `techdebt/spire-run-worker/3-2-changekind-…`
- [sec/L3] The push gate's floor misses `.tekton/**` and a symlink at a protected parent —
  `techdebt/spire-workspace/3-2-…`
- [sec/L2] A check-then-open race on an agent-writable bundle path — `techdebt/spire-workspace/4-2-…`
- [cr/I2, cr/S4] `RunRuntime.destroy` cannot report a partial failure and `DockerRunRuntime` is 437
  code lines. Both fold into the SPI-contract entry, which is where the destroy contract belongs.
- [sec/H1] **A cancel before the run starts is accepted and dropped.** `register` runs only after
  `create` returns and `create` blocks on the clone, so a queued, cloning or dispatch-uncertain run
  takes a 202 and starts anyway. Two task reviews each closed the half they could see — Task 7 the
  executing case, Task 9 the uncertain one — and the gap is before either. Not fixed in this batch:
  it changes the dispatch path's ordering and the claim table's meaning, and wants its own round with
  a test that fails when the pre-create check is deleted.
  `techdebt/spire-run-worker/2-3-a-cancel-before-the-run-starts-…` and `UNVERIFIED.md` §A3, which is
  the one §A entry no guard covers — a source scan sees a producer appear, not an ordering change.

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [cr/S2] Extract one publish primitive across three ack ladders. The reviewer checked and confirmed
  it is not a live defect today (a duplicate `RunFailed` touches zero rows because the projection
  guards on `LIVE`, and the charge is absorbed by `UNIQUE (call_ref, token_type)`). A prior round
  dismissed it for the same reason; the residual drift is worth one collaborator when the dispatcher's
  compaction path is next touched.
- [rules] Nine commit bodies open "Four-lens review of X found…". The rules lens read these as
  motivation rather than authorship and asked for a ruling: I agree — the binding rule forbids review
  rounds presented AS authorship, and a human team can run a review with four reviewers. Plainer
  wording from here; not rewriting forty pushed commits over a phrase that claims nothing.
