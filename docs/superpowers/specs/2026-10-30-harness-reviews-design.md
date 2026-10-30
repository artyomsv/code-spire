# Reviews on a harness (Codex subscription) — design

Status: design, 2026-10-30. Not started.

## Why

Every review today is one or two calls to an LLM API, paid per token by the global default provider's key. The
operator already pays for a Codex subscription that the factory uses (M3.5: seats, sign-in, renewal, cost 0 with
tokens still counted). The API bill for reviews is the cost to cut. The operator also wants the freedom to choose, per
repository, how a review uses AI.

A harness review is also a better review: an agent that holds a checkout of the repository can read the code around
the change, the callers of a changed method and the project's rules, which a single prompt with a diff and some context
cannot.

## What the operator decided

1. **Per-repository choice.** Each repository has a review setting, "Review with":
   - **API** — an LLM provider and a model (today only the global default exists; this makes it per repository).
   - **Harness** — a harness type (Codex today), a model, a thinking level, and how it pays (a subscription seat or an
     API key).
   A repository without the setting reviews exactly as today, on the global default API provider.
2. **The harness agent reads the repository.** It works on a checkout of the pull request, reads nearby code and
   dependencies, and produces a proper code review: findings with severity, file and line, and the review's state.
3. **The reviewer role, read-only.** The run uses the repository's registered reviewer account (the REVIEWER role
   binding). The review may read the code and Code Spire posts comments and the review with that account, as today.
   Nothing in the run can change the codebase: no push, no write credential.
4. **The review's quality bar is the operator's own `review-all` skill** (dotai `codex/skills/review-all/SKILL.md`):
   four lenses — security, correctness and design, project rules, tests and build; load the repository's rules first;
   read every changed hunk, then the whole changed method and its callers; every finding names file and line in the
   reviewed commit, a concrete failure scenario and a fix; "no findings" only after every lens says what it checked;
   tests and build are judged from CI results, never by building.
5. **Seats, as the factory does it.** A seat (or key) is chosen once, when the review starts, from the harness's pool;
   with more than one, the pool's existing rotation applies. A running review never switches seat or harness.
6. **A spent pool stops the review.** When no seat or key of the chosen harness is available (rate-limited, refused,
   none signed in), the review stops and a warning appears in the attention feed. It does **not** fall back to the API.
7. **Extensible.** Codex is the first harness. Others (Claude Code, Pi) plug in through the same harness layer later.
   Claude Code can only pay with an API key: Anthropic's terms forbid automated use of a subscription
   (`docs/factory/EXECUTION-LAYER.md` §2, ADR-031).
8. **Done means:** on a real pull request (`spire-test`), a review paid by the Codex seat posts a proper review —
   findings with their statuses, and the review state/verdict — through the existing posting path, at cost 0 with its
   tokens recorded.

## Goals

- Per-repository review setting (API or harness), editable in the repository's settings beside the factory setup.
- A harness review that produces the same result the reviewer produces today, so everything downstream is unchanged:
  inline findings, the summary, the verdict (ADR-047 on GitHub, comments elsewhere), finding statuses, re-review
  reconciliation, conversation, charge ledger, analytics, learned-memory filtering.
- Reuse the factory's harness layer (`HarnessAdapter`, `CodexAdapter`, `HarnessRegistry`), credential pool and seat
  life cycle (`harness_credential`, sign-in, `SeatRenewal`), model catalogue admission (`HarnessCatalogues`), run units
  (`RunRuntime`, `DockerRunRuntime`), the read-only run topology (ADR-049) and scrubbing (`RunFailures.scrubFor`).
- Harness-neutral domain code: no Codex type name outside the adapter and composition roots.

## Non-goals

- Changing the API review path, its prompts or its parameters.
- Letting the agent post to the forge, run builds or tests, or reach CI itself (CI results, if used, are given to it).
- Claude Code or Pi implementations (only the seam must admit them).
- Automatic fallback from a harness to the API, or from one harness type to another.
- Per-repository spend caps (the existing global caps and `SpendGate` apply).

## Anti-goals (a failure even if it runs)

- A harness review that posts something the API review would not accept: free text instead of findings, findings
  without a file and line in the reviewed commit, or findings outside the diff presented as inline comments.
- Any write path from the review run to the repository.
- A seat review billed as metered, or recorded without its tokens.
- A spent pool turning into an unnoticed stall: every stop must reach the attention feed.

## Constraints

- ADR-018 rules subscription backends out of the reviewer. A new ADR supersedes that rule for harness reviews and
  records why (operator decision, cost, the read-only boundary).
- ADR-029: harness runs never execute inside `spire-review-worker` (Docker socket, long runs, its ordered consumer and
  900 s ack ceiling). Harness reviews run on the run-worker.
- Domain events are appended only by the aggregate (ADR-010); everything between components is an async command or
  event; messages keyed by `reviewId`.
- Diffs are never persisted (ADR-011); sensitive content Tink-encrypted at rest (ADR-014); run secrets scrubbed from
  logs, transcripts and artifacts.
- A run unit's overhead is about 1–1.5 minutes (`docs/factory/M35-ACCEPTANCE.md`); a harness review is slower than an
  API review by at least that.
- Vendor terms are re-read before the arm ships (`EXECUTION-LAYER.md` §2); the Codex seat position rests on an OpenAI
  support answer, not a published term.

## Calls made by the designer (marked; the operator has not ruled on them)

- **Where the run happens.** The orchestrator, on `ContextAssembled` (where `WorkerLlmCredentials.resolveDefault` is
  called today), reads the repository's review setting. For a harness review it funds the run like `RunFunding` does
  (catalogue admission, spend gate, seat or key) and sends a new run command to the run-worker instead of
  `GenerateReview` to the review worker. The result returns as a run result and is translated by a saga into the same
  outcome `ReviewGenerated` carries today.
- **The run unit.** The read-only topology: init clones the pull request head with the reviewer account's read
  credential; the agent gets the model credential only; the publisher is `spire-publish-none`. The agent's answer is
  the run's artifact (`$SPIRE_ARTIFACT`, read back as `ReadOnlyResults` does), and must be the reviewer's findings JSON
  — the same contract `FindingsParser` reads. An answer that does not parse makes the review degraded, as an
  unparsable API answer does today.
- **The prompt.** The repository's REVIEW prompt (operator-editable, per scope) still supplies the persona and
  instructions; a harness-only preamble adds the agent's working rules (the four lenses, read callers, never edit,
  write the JSON to the artifact path). The diff, the base and head commits, assembled context (issues, rules, code
  context) and prior open findings are handed to the agent as files in the workspace, not only as prompt text.
- **Re-reviews.** A re-review of a new commit is one harness run: it is given the prior open findings and returns both
  the reconcile verdicts and the new findings, so it does not pay twice for the run unit. The two parts keep the
  existing contracts (`VerdictsParser`, `FindingsParser`).
- **Conversation replies.** A reply to a finding thread uses the same repository setting and runs as a harness run.
  It is short, but it gets the checkout, which is the point of the setting.
- **Charges.** A review's charges record which credential paid (`credential_ref`, filled for reviews too). A seat run
  is charged through `priceUnmetered` (tokens recorded, cost an asserted zero); a key run is priced as today and needs
  complete pricing for the model.
- **Attention.** A stopped review (pool spent, catalogue refused the model, seat refused by the vendor) raises an
  attention item naming the repository, the harness and the reason; the review shows as refused, not failed.
- **Timeouts.** A harness review has its own wall-clock bound (configured, not reusing `LlmTimeoutBudget`); an overrun
  is a failed review with its reason, retryable like a provider failure.
- **Per-repository API choice.** "API" with no provider chosen means the global default, so the existing behaviour is
  the setting's default value and needs no migration of rows.

## Left to the builder

- The names of the new command, result and saga; the table and columns for the repository review setting.
- How the UI places the setting (it belongs beside the repository's factory/build setup and the "Reviewer may approve"
  setting).
- Whether the workspace files for diff and context are written by the init container or delivered another way.
- The exact harness preamble text, kept short and tested against the findings contract.
- How the live proof is run on `spire-test` and recorded (an acceptance record like the factory's).

## Answers after the builder check (they override the sections above)

1. **Conversation replies move to the harness in this delivery** (operator, 1B). A reply on a harness repository is a
   harness run on the same repository setting: the agent gets the checkout, the finding, the thread history and the
   new comment, and writes a free-text answer as its artifact. Code Spire posts it through the existing reply path, so
   the thread's idempotency (thread, triggering comment), turn caps and backoff stay where they are. The run overhead
   per reply is accepted. The acceptance adds: a reply on the seat answers a finding thread on `spire-test`.
2. **Code Spire decides the review state, as today** (operator, 2A). The agent reports findings (and, on a re-review,
   reconcile verdicts) only; `ReviewVerdictDecision` derives the verdict from open blockers. The harness preamble does
   not ask the agent for a verdict.
3. **Fork pull requests are reviewed** (operator, 3B; there are public repositories with contributor pull requests).
   On GitHub and GitLab the init container fetches the pull request's own ref (`refs/pull/<n>/head`,
   `refs/merge-requests/<n>/head`) as well as the base. Bitbucket Cloud has no such ref: a fork pull request on a
   Bitbucket harness repository is not reviewed and raises an attention warning naming the reason.

## Designer's calls after the builder check (marked; not ruled on by the operator)

- **A review run is a run with a review kind.** It appears on the Runs screen with its transcript (encrypted at rest
  like any run transcript that may quote source, ADR-014) and its failures. It is charged once, under the review's
  call slot as `REVIEW`/`RECONCILE`/`FOLLOWUP` with `credential_ref` and every token bucket the harness reports; the run
  plane's own charging skips review runs, so nothing is charged twice.
- **The agent makes the diff itself.** Init fetches base and head (and the pull request ref, decision 3); the agent
  runs `git diff` in the checkout. No new forge credential enters any container, and the orchestrator grows no diff
  client. The finding thread history a reply needs, and the prior open findings a re-review needs, travel on the run
  command from the orchestrator's stored state (encrypted), not fetched by the agent.
- **A newer head cancels the older run.** When a pull request gets a new head while its harness review is running,
  the orchestrator sends `CancelRun` for the superseded run, so a seat's window is not spent on a stale commit.
- **Concurrency is bounded.** A setting caps how many harness reviews run at once (per deployment); further reviews
  wait in order. Factory builds keep their own share of the run-worker.

## The untrusted-code risk, and the operator's answer

A fork pull request's code is written by anyone. In a harness review an agent with a shell reads that code while its
container holds the model credential (a seat's access token lives about ten days; refresh tokens never leave the
orchestrator). On the Docker arm a run container's egress is open (`docs/SECURITY.md`, the HIGH techdebt
`2-3-a-run-unit-has-no-network…`), and Codex runs without its own sandbox inside the container (`CodexAdapter`:
bubblewrap cannot start under Docker's default seccomp). A prompt-injected agent could send the token out. The factory
does not meet this: its tickets come from labelled, allowlisted actors.

**The operator chose to close the network first** (B): harness reviews review every pull request, fork ones included,
and they ship only once a review run's agent can reach nothing but its model endpoint. Consequences:

- **Network isolation is the first slice**, before any review runs on a harness. The agent container of a review run
  sits on a network with no route out except an egress proxy that allows only the hosts its harness declares.
- **Each harness declares its egress hosts** through the harness layer (Codex: the model API and the sign-in/token
  host it calls; a future harness declares its own). An undeclared host is refused, and the refusal is visible in the
  run's failure reason.
- **The init container keeps forge access** (it clones with the reviewer's read credential) and the agent never shares
  its network; the agent cannot reach the forge.
- The same isolation should apply to factory runs; whether this delivery switches it on for them is a planning
  decision, recorded with its reason. Closing it for review runs closes the HIGH techdebt item for them.
- **Acceptance adds:** from inside a review run's agent container, a request to any host but the declared ones fails,
  and the declared model host works (a live check on the dev stack, and a test against the run unit).

Mechanism details (proxy image, how the agent is pointed at it, how the allowlist is rendered) are left to the builder,
within these limits: no `seccomp=unconfined`, no new privileges on the agent container, and the allowlist comes
from the harness, never from the reviewed repository.
