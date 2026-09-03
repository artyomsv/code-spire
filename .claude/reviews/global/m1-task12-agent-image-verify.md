# Code Review State: global / m1-task12-agent-image-verify

Last reviewed: 2026-09-03
Rounds completed: 1

Covers commit `a00abf9` (Task 12 — `spire agent-image verify`, FR-F13's M1 half). Four lenses:
security-officer, code-reviewer, rules-compliance, qa. Semgrep 14/14 files, 0 findings.

**The round's shape: almost every finding was the checker lying about an image.** Two false verdicts
an operator would act on, one design defeated at the output layer, and a published contract that was
not sufficient to build against — which is its whole purpose. The three claims most worth keeping
were each verified independently before being acted on, because this project has already had one
confident agent diagnosis turn out to be false.

## Resolved (fixed in code; do not re-raise)

- [qa/F1] **A checker-side setup failure was reported as three image defects.** `echo base=$(git
  rev-parse HEAD)` prints the literal `base=` when git fails, and a `startsWith` check accepted the
  empty string; the entrypoint then aborted on `SPIRE_BASE_COMMIT` and three clauses each named a
  specific defect the image did not have. **Reproduced on the real reference image using Mode S step
  3's own commands** — 8/8 PASS, then 4 FAIL, three of them false. The root cause is the very thing
  the mount-points clause exists to catch: git refuses a workspace owned by a different user than
  the process. A blank answer is a failure now, and the three clauses read `NOT CHECKED`. This also
  explains the "root produced four failures" that an earlier IT javadoc admitted it could not — round 1
- [qa/F2, code-quality/C1] **`USER root:root` passed the non-root clause.** The check tested the
  whole string against `root`, `0` and a `0:` prefix, so a documented Dockerfile form matched none
  and an image running as uid 0 conformed — on the clause whose purpose is that this container runs
  untrusted model output at full shell access. The uid field decides it, and all ten spellings are
  enumerated — round 1
- [code-quality/C2, rules/4] **The trust-store test read `A || B || C && D`.** POSIX gives `&&` and
  `||` equal precedence with left associativity, so `D` gated all three alternatives and the whole
  expression reduced to "`/etc/ssl/certs` is non-empty" — an image whose store is only
  `/etc/ssl/cert.pem` was told it had none. Measured in a shell. Invisible to the suite because the
  "isolated break" image deleted both paths, so it passed either way — round 1
- [security/M1] **A hostile label could forge `PASS` lines and conceal the verdict.** Docker stores
  ESC, CR and LF in a label verbatim (measured), so the two halves separated in the data model could
  be re-blended on the screen an operator is told to read. Every image-controlled string is stripped
  of control characters in the record constructors and length-bounded at its source — round 1
- [security/H1] **Probe containers ran image-chosen code with the default network and full
  capabilities**, while the class javadoc promised neither and said the checker was not a way to run
  arbitrary work under the operator's daemon. On a CI runner that reaches the internal network. Every
  probe question is local, so they now get `--network none`, `no-new-privileges` and `cap-drop ALL`.
  The security lens corrected the brief's premise here rather than accepting it: the runtime arm does
  NOT set `--network none`, and cannot, because its agent has a model API to call — round 1
- [rules/3] **The contract was not sufficient to build against.** Three clauses are only passable by
  an image honouring five `SPIRE_*` variables the document named nowhere.
  `ContractAndCheckerAgreeTest` could not see it — comparing ids proves the two lists agree, not that
  what they describe is complete. It now derives every variable the reference entrypoint requires and
  fails when the document omits one — round 1
- [rules/1, qa/F4] The contract claimed the reference image declares `node`; it carried no labels at
  all, so Mode S step 1 printed `(no label)` twice. Labels added — the reference image should
  demonstrate the half of the report FR-F13 is mostly about — round 1
- [rules/2, qa/F3] **`mount-points` documented ownership and tested writability.** Root can write a
  1001-owned directory, so the clause written to catch a mount-ownership mismatch passed on the one
  image where the mismatch was real — and that mismatch is what caused F1. Both are tested now, the
  dead `id -u` line that looked like this check dropped is gone, and the doc says what is checked — round 1
- [code-quality/I1, security, rules/16] **A single unreachable clause exited 1** — "this image is
  wrong" — while the CLI's own comment said that outcome must not happen and the runbook documented
  behaviour the code did not have. Carried as a typed field, not a detail-string prefix — round 1
- [code-quality/I4] `DONE` absent and `DONE` written early are three answers now. A boolean printed
  the second about an entrypoint that had done the first, while the shell had already computed the
  distinction — round 1
- [code-quality/I4b] The `2>/dev/null` bound to the last branch only, so `find`'s stderr survived,
  merged into the output, and became a phantom filename that flipped the clause — round 1
- [code-quality/I2] `unreachableClauses` hardcoded six ids that had to track `Clauses.VERIFIED`.
  Derived now: a ninth clause cannot be silently omitted, which is the failure `unknown` exists to
  prevent — round 1
- [code-quality/I3] Three clauses conflated "the probe gave no answer" with "the answer was no", each
  then printing a detail naming the wrong cause. `mountPoints` has a null path and the handoff clauses
  report `NOT CHECKED` when the entrypoint never reached the harness — round 1
- [security/M3] Log capture was unbounded into the heap. A hostile image answers that with an
  `OutOfMemoryError`, which is not a `RuntimeException`, so no catch on the path would see it and the
  command would die without a report — round 1
- [security/M4, code-quality/I6] Containers and volumes are labelled and force-removed **with their
  anonymous volumes** (an image's own `VOLUME` instruction otherwise leaves one behind — measured);
  both volumes are created inside the `try`; the log callback is closed on every path; the stamp is a
  UUID, because a duplicate volume name silently REUSES rather than erroring — measured — so the
  `finally` would have deleted someone else's — round 1
- [security/L1] A timeout is an image fact reported as one, and is no longer paid twice — an image
  whose entrypoint blocks read as a busy daemon after twice the advertised wait — round 1
- [security/L2, rules/5, code-quality/I5] The third-party listing image is gone. Nothing pulled it,
  docker-java does not pull on create, and it was needed *after* the expensive probe had run — the
  lesson `DockerRunRuntimeIT` already records. The image under test has a shell, which the git clause
  has established by then — round 1
- [qa, rules] **`AgentImageCli` had no test**, and the split that was made to enable one could not
  have delivered: `run` built its own Docker client, so exits 0 and 1 were unreachable. It takes the
  report as a function now; all three codes and the usage text are asserted with no daemon — round 1
- [qa] Nine surviving mutations closed — including that the `git` clause had **no failing case
  anywhere**, the second `unknown` path was entirely unasserted, and nothing tested an image that
  never writes `DONE` — round 1
- [security/L3] The stdin clause justified itself with "argv is visible in `docker inspect`", which
  does not hold: the prompt travels in the environment, which `inspect` prints too. The real reasons
  are the process list, the autosave, and shell quoting — round 1
- [rules/8] **The techdebt entry filed with this task was a duplicate.** An entry for the same test,
  scheduler and fix was filed the day before; `techdebt/README.md` names that as the specific failure
  it exists to prevent. Deleted and folded into the original — including that the duplicate reasoned
  from the *worse* of the two fixes the original already listed — round 1
- [rules/10-13] `CLAUDE.md` gained the M1 bullet and the three counts this change moved (six service
  modules, four Docker-lock modules, the new contract doc) — round 1
- [code-quality/S2, rules/6] `booleanClause` took four parameters, two of them transposable prose. A
  `ClauseText` record — round 1
- [rules/9] The IT's two boolean flag parameters are three named builders — round 1
- [code-quality/S7] `repoRoot()` guessed from the working directory; it is handed in as
  `spire.repoRoot`, the pattern `spire-arch` already established one module away — round 1
- [code-quality/S8] The IT leaked a temp build context per call and built the conforming image twice
  — round 1
- [code-quality/S11] The verified list could hold a declared clause id (defence by test only), and an
  empty verified list rendered as CONFORMS. Both refused at construction — round 1
- [rules/15] Mode S's troubleshooting row described a state that path cannot reach, and Mode S needed
  its JDK requirement stated — round 1
- [rules/26] One spelling of the command: there is no `spire` umbrella binary — round 1
- [code-quality/S1, rules/22-23] Dead code: the unread `id -u`, the unreachable `writable` arm — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [code-quality/S6, rules] Replace the test's reflection into docker-java with a wrapper interface.
  Declined, and both reviewers independently agreed: a wrapper puts our own shape between the checker
  and the library, and the belief most likely to be wrong is what the library returns — C1 was exactly
  that. A wrapper would have made the fake agree with the wrong belief.
- [rules/7] `System.out` in a CLI. A CLI's stdout is its product, not its telemetry, and the codebase
  precedent is `spire-publisher`, which reports via stdout by design and ships `slf4j-nop`. This is
  stronger than the precedent: only `main` names `System.*`.
- [rules/27] Make the probe timeouts configurable. A tuning knob with a defensible default; the LLM
  path's lesson applies to a value an operator's model can outrun, not to a shell script.
- [code-quality/S5] Fold the two listing containers into one. Real, and it now costs one container
  rather than two third-party ones; the remaining saving is a container per verify against a clarity
  cost in a shell string that already hid one defect.
- [rules/14] Convert the debt entry to the table template. Moot — the entry is deleted as a duplicate.
- [rules/18] Extract every magic value. The probe answer keys and the values are constants now; the
  remaining literals are single-use and named at their point of use.
