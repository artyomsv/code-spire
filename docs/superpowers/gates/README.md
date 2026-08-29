# ADR-026 §9 evidence gate — the harness, and what it measured

Reviews a set of pull requests **twice** through the real pipeline, toggling only the code-context
provider, and diffs the findings. Written to answer one question: does giving the reviewer the
definitions a diff depends on produce findings it would otherwise miss?

Run 2026-08-29. **Result: null — rung 2 was not authorised, and P3 closed at rung 1.** The numbers
and, more importantly, why the null does *not* mean the feature was falsified, are in the spec's §9.

## Running it

Needs the dev stack up (`docker-compose.dev.yml`), a registered SCM provider, a registered
context provider of type `code`, and a default LLM provider.

```bash
./adr-026-gate.sh run_gate 38 40 42 43 61      # each PR: control arm, then treatment arm
./adr-026-gate.sh run_variance 38 42           # the noise floor: same arm twice
node compare.js .                              # diff the arms, print what needs judging
```

`REPO=<slug>` selects the repository (default `code-spire`). The harness leaves the code provider
enabled when it finishes, whatever state it found.

**It spends real money** — roughly 25–45¢ per review on a large diff, so a six-pull-request run with
a noise floor is around $4.50. Configure `caps.spend-millicents` before a long run.

## The three controls, and why each exists

Every one of these was added because its absence had already produced a wrong answer.

**Both arms must be FIRST reviews.** ADR-019 turns a re-run into a reconcile carrying an exclusion
list, which suppresses exactly the findings the arms are compared on; and the LLM idempotency claim
would re-emit the first arm's stored result rather than spending a new call. Hence the verified reset
between arms — verified because `psql -c` runs multiple statements in one implicit transaction, so a
single bad identifier silently rolls the whole reset back and the harness reports success.

**The arms must actually differ** (`capture.js`, arm-aware). The control arm must receive zero code
snippets and the treatment arm must receive some, read from the worker's own log line rather than
assumed from the provider toggle. The first version windowed that log by line offset —
`docker logs | wc -l` is not stable between calls — and the control arm captured a context line from
a review that had run the *previous day*, reporting code context in the one arm defined by having
none.

**A run must have produced something** (`degraded = false`, status `completed`). The gate stops rung 2
on a null result, so it has to tell "code context did not help" from "this run produced nothing". The
first attempt at the gate hit exactly that: every review came back empty because the model spent its
whole output budget thinking, and both arms would have reported zero findings. A measurement whose
real subject was a token cap.

## The control that was still missing

All three above verify the *pipeline*. None of them verifies that the **corpus** can discriminate,
and that is what the 2026-08-29 run foundered on: the pull requests measured produced 3 code findings
against 15 documentation findings, and code context can only ever change a code finding.

A future run should require that the corpus is majority code with cross-file dependencies, and should
always run `run_variance` alongside — at the observed variance, a single pair cannot resolve the
effect. On one pull request, two *identical* runs differed by five findings.
