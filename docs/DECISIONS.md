# Decisions

Architecture decision records for Code Spire. Newest first.

---

## ADR-035 — Repository-supplied configuration may narrow behaviour, never redirect compute or widen authority

**Context.** Three defences already in this codebase turn out to be the same rule, discovered
separately each time. `.codespire` is read from the pull request's **target branch, never the
reviewed commit**, because the head is written by the change under review and rules taken from it
would let a pull request rewrite the reviewer's instructions in the same pull request. Codex CLI —
found during the factory research — ignores `model_provider` and `model_providers` set in a
repository's own config file, in its own documentation's words *"to prevent repositories from
secretly changing the machine's model provider."* And the factory adds two more instances: a
repository-named agent image would decide where the operator's credentials are injected, and an
autonomy label applied by anyone with tracker write would decide what the factory may merge.

Rediscovering the same rule a fourth time by being bitten is the outcome this record exists to
prevent.

**Decision.** State it once, cite it thereafter:

> Repository-supplied configuration may **narrow** behaviour. It may never redirect where compute or
> credentials go, and it may never widen authority.

Concretely, for every setting a repository can influence: a repository may make the reviewer
stricter, exclude a path, lower an autonomy profile, or add text into an already-fenced untrusted
slot. A repository may never select the model endpoint, the container image, the credential, the
runtime, or a profile above the operator's ceiling. Where a repository-facing selection is genuinely
useful — an image for its own toolchain, a profile for its own risk appetite — it selects **from an
operator-defined allowlist**, never by free text.

**Why the fencing defence does not cover this.** Prompt fencing works on untrusted *data*: a ticket
body, a review comment, a rules file. It quotes the input and tells the model not to obey it. None of
that helps for untrusted *control*, because control is not quoted into a prompt — it is read by code
and obeyed. A label naming an autonomy profile is not text the model might be tricked by; it is a
switch. The only defence is a ceiling and an allowlist.

**Consequence.** Every new repository-readable setting is checked against this record before it
ships, and the check is named in its design. It also explains, retroactively, why the target-branch
rule for `.codespire` could not be solved by better fencing: the defence had to live in *where the
rules were read from*, not in *how they were quoted*.

---

## ADR-034 — The platform divides into capability packs; entitlement is one gate and every charge names its capability

**Context.** The factory makes Code Spire large enough that a customer may reasonably want part of
it. Today's module structure splits by *technical seam* — contract, diff, llm, scm-\*, context-\* —
and carries the ADR-021 licence split. That axis does not answer "what did this customer buy",
because one purchasable capability spans several modules, a worker, several UI screens and several
read-model tables.

**Decision.** Two orthogonal axes, both holding at once. Code modules keep splitting by seam and
keep carrying the licence. **Product modules** ("capability packs") split by capability: **Core**
(chassis, not sold alone), **Review**, **Knowledge**, **Build**, **Autonomy**, **Insight**. The
boundaries and their reasoning are in `docs/factory/PACKAGING.md`.

**Build and Autonomy are separate on purpose.** Build is *"I ask, it builds"*; Autonomy is *"it
decides when to work"*. That is a genuine value line, and the second half is the one with the blast
radius, so gating it separately is a safety control that happens also to be a price line.

**Knowledge and Build share connectors and stay separate**, because the difference is rights rather
than transport: a context provider reads an issue, a work source also claims, comments and
transitions it. Authority narrows per pack, enforced at the adapter.

**Entitlement is read at exactly one place** — in the saga, beside `SpendGate` and the priceability
check — so that every reason a dispatch was refused reads in one place. A blocked capability produces
a first-class refusal (`entitlement_missing`) with its own status, timeline detail, operator note and
attention row, reaching the UI's status union in the same change. Not a crash, not a silent skip.

Scattering the check as `if (hasBuild)` across handlers is refused explicitly. This codebase has
twice paid to learn why one comparison must have one home: `SpendGate` exists so a money comparison
cannot drift between the enforcement site and the attention row, and `ProviderCircuits` exists so
health is decided once per host rather than per call site.

**`llm_charge` gains `capability` and `credential_ref`, now.** Per-module pricing asks exactly one
question — what did this capability cost this deployment this month — and it is unanswerable without
the column. It is **impossible to backfill**: a charge row that did not record its capability cannot
have one inferred later. This is the same lesson as `review_finding` shipping with no backfill, and
the same failure shape as ADR-023's four places where *unknown* silently became *zero*. An unmetered
run still records its capability and its credential, because money is null for it and call counts are
not.

**Placement.** `Entitlements` is a value type in `spire-contract`, because commands carry it across
the wire. **Enforcement lives in the FSL services**, because no Apache-2.0 module may depend on a
service module — the same constraint that kept the LLM circuit breaker out of `spire-llm`. Licence
and entitlement answer different questions and neither substitutes for the other: FSL stops a
competitor reselling the services; entitlement decides what the operator's own deployment switched
on.

---

## ADR-033 — Run events are a second tier; the aggregate's log stays milestone-only

**Context.** One agent run in the observed prior art emitted **858 events** — reasoning, tool calls,
tool results, state transitions. A review, by comparison, produces a handful of domain events. ADR-010
makes the aggregate the single writer of domain events and ADR-011 encrypts payloads that may quote
source. Writing an agent's tool-call trace into that log would multiply event-store volume by three
orders of magnitude, encrypt every line of it, and make replay useless.

**Decision.** Two tiers, with different transports, stores, retention and guarantees.

The **run stream** — reasoning, `tool_use`, `tool_result`, output, state — rides a new topic
`cs.run-events` into a bounded `run_event` table with a short TTL. It exists for the live tail, for
debugging, and for a transcript an operator can read. It is not replayable and nothing derives state
from it.

The **domain tier** is unchanged: `RunStarted`, `RunFinished`, `BranchPushed`, `GateOpened`,
`GateResolved`, `WorkItemCompleted` and their siblings ride `cs.events` into the event store, durable
and replayable. Only milestones are promoted.

**Why the normalized run-event types live in `spire-harness` rather than `spire-contract`.** Most run
events never reach the domain log. Putting the high-volume vocabulary into the contract module would
imply a durability guarantee the tier does not have, and would invite a later change to persist it
"since it is already in the contract".

**Consequence.** `run_event` payloads are Tink-encrypted, because a tool result quotes the
repository — the ADR-011 boundary applies unchanged. The TTL is an operator setting, and a run's
transcript is therefore explicitly ephemeral. An operator who needs a permanent transcript exports it
before expiry; the alternative — permanent retention by default — is a storage bill and a data
exposure nobody asked for.

---

## ADR-032 — Autonomy is a property of the work item, selected by label, bounded by an operator ceiling

**Context.** A single deployment-wide autonomy setting is wrong in both directions: it makes a typo
fix wait for a human, or it lets a change to an authentication path merge without one. The prior art
splits on this — one system gates per campaign, another triggers per label — and neither binds the
two together.

**Decision.** Autonomy is a property of the **work item**. An operator defines named **profiles**;
each profile is a **vector**, assigning every phase (`intake`, `spec`, `plan`, `build`, `verify`,
`review`, `deliver`, `land`) a mode of `auto`, `approve` or `off`, plus caps for runs, steps, wall
clock, spend, call count and protected paths. A work item selects a profile by tracker label.

Three rules make a label safe to obey.

**The ceiling is operator-owned and does not live in the repository.** Profiles, the ceiling and the
label mapping live in the registry. This follows the distinction this project already drew for
prompts: a per-repository prompt is an operator-owned change to the reviewer's *instructions*, while
`.codespire` is contributor-owned *data* that can only add text into a fenced slot. Autonomy is
instructions with authority attached, so it sits on the operator side.

**A label may only lower.** A label naming a profile above the ceiling is clamped to the ceiling, and
the clamp is recorded as a timeline entry and an attention row. Visibly, always; never silently, and
never upward.

**The labeller must be allowed.** Anyone with tracker write can apply a label, so the label is an
authorization decision made outside this system's trust boundary. The existing per-provider author
allowlist is reused: a label applied by an actor outside it does not select a profile. Without this
rule, a drive-by contributor opens an issue, labels it for full autonomy, and the factory writes and
merges their code using the operator's credentials.

**Gates are durable state, not blocking calls.** An `approve` phase persists a row that appears in the
existing attention panel and is answerable from the dashboard, a tracker comment, or a pull-request
review — three channels, so a human never has to learn a new tool to unblock work. Gates **expire**
into `WorkItemRefused(gate_expired)`, because a plan awaiting approval for a month is dead work
holding a budget reservation. A person pushing to the branch or commenting on the pull request sets
`human_takeover` and suspends automation until an operator resumes: an agent that races a human
produces conflicting commits and a bot arguing with a maintainer in public.

**Every refusal is a first-class terminal state with its own reason**, in the vocabulary shape ADR-025
established — and every one of them reaches the UI's status union, label map, pipeline renderer and
chip filter in the same change. A status the union does not know arrives as runtime JSON and renders
through the default branch, which is the *success* branch, while `tsc` has nothing to check. That has
happened twice here; the second time a refused review rendered as five green segments under "done".

---

## ADR-031 — The agent image is a conformance contract, not a base image

**Context.** The obvious design ships `spire-agent-codex` and tells customers to write
`FROM spire-agent-codex`. A regulated enterprise cannot: it builds from an approved golden base,
mirrors images into its own registry, scans and signs them, and injects its own CA bundle and proxy
configuration. Inheriting from a vendor's image is not available to them.

There is also a second dimension that a single image cannot carry. The sandbox needs the
**repository's own toolchain** — a JDK and Gradle here, a Python environment there — or the agent
cannot run the repository's tests, and a test it cannot run is back-pressure it does not have.

**Decision.** Publish an **image contract** and a conformance command; ship images as reference
implementations that satisfy it. An image must place the harness binary on `PATH` under its declared
name, declare and run as a non-root UID, contain `git` and a POSIX shell, honour `SPIRE_WORKSPACE`,
write the run event stream to the declared descriptor, honour proxy and CA-bundle variables, exit
non-zero only on failure, and contain no credential. `spire agent-image verify <ref>` boots any image
and passes or fails it.

This follows the house rule that a seam is not done until a gate enforces it, and matches how the
comparable protocols in this space prove an implementation — with a conformance suite, not a grep.

**Shipped images are one base with thin per-harness layers**, not four independent images: four
images means four places to patch a CVE and four entrypoints that drift. This repository already
carries three tech-debt entries that are literally *"duplicated with no drift guard"*. Images are
digest-pinnable, signed and published with an SBOM, because an enterprise mirrors and approves them
before anything else can happen.

**Harnesses are installed into images, never redistributed.** Claude Code publishes no open-source
licence; Codex is Apache-2.0 and pi is MIT and could be shipped, but all four are installed the same
way so that adding a proprietary one later is not a special case. The closest prior art states the
same rule in the same words.

**Image resolution is `per-run > per-repository > harness default > built-in`, and the
per-repository level selects from an operator allowlist** — ADR-035. A repository-chosen image
decides where the operator's credentials are injected; free text there means anyone who can open a
pull request receives the model key, the git token and the CA bundle on startup.

**Sidecar injection is named and deferred.** Injecting the harness into the customer's own image at
run time — the pattern major CI runners use — is the better end state and needs a bundled runtime for
the Node-based harnesses. The contract makes deferring it safe: a customer who needs it today builds
an image that passes the suite.

---

## ADR-030 — Model credentials belong to the operator, and API keys are the only supported auth mode

**Context.** A factory spends money on every run, under some vendor's terms. Two questions had to be
separated, because conflating them is the easiest mistake in this area: the **harness software
licence** (may we run and ship this program?) and the **model provider terms** (may this credential
run unattended?). `pi`, `opencode`, OpenHands and Goose have only the first — they are empty
harnesses pointed at somebody else's API, so there are no terms of their own to check. Codex CLI and
Claude Code carry both, because each is a vendor's own client for that vendor's own service.

Primary sources, retrieved 2026-09-01 and quoted in full in
`docs/factory/EXECUTION-LAYER.md` §2:

- **Anthropic Consumer Terms** (effective 2025-10-08) list among prohibited uses: *"Except when you
  are accessing our Services via an Anthropic API Key or where we otherwise explicitly permit it, to
  access the Services through automated or non-human means, whether through a bot, script, or
  otherwise."* Enforced server-side since January 2026.
- **Anthropic Commercial Terms** (effective 2025-06-17) contain no automation restriction, and §A.1
  explicitly permits using the Services *"to power products and services Customer makes available to
  its own customers and end users."* §D.4 forbids building a competing service **or reselling the
  Services** except as expressly approved.
- **OpenAI Services Agreement** (effective 2026-01-01) contains no automation clause; the consumer
  Terms of Use prohibit only *"Automatically or programmatically extracting data or Output."*
- **OpenAI Codex documentation:** *"Use API key authentication for programmatic Codex CLI workflows,
  such as CI/CD jobs."*
- **OpenAI Service Terms:** *"Output generated by code generation features of our Services, including
  OpenAI Codex, may be subject to third party licenses, including, without limitation, open source
  licenses."*

No document was found that **affirmatively permits** using a ChatGPT subscription for unattended
automation. It is not prohibited in writing, and OpenAI's own documentation steers to API keys.

**Decision — three parts.**

**Bring-your-own-key, always.** The operator supplies the credential, it is stored in the existing
encrypted registry, and the usage bills to the operator's own agreement with the vendor. This is not
merely a cost preference: Anthropic §D.4 forbids reselling the Services, and any design in which Code
Spire proxied model access would engage that clause. The provider registry already implements this
posture; the factory inherits it rather than inventing a second one.

**API key is the supported auth mode**, and the `HarnessAdapter` contract has **no subscription auth
mode** — not a flag, not a warning, absent. Anthropic prohibits subscription automation outright;
OpenAI permits it only by silence. A subscription is a *person's* credential and a factory is a
*service*, browser OAuth in a headless sandbox is operationally absurd, and one server-side policy
flip would take every deployment dark at once. Where an operator nonetheless chooses subscription
auth for a harness that supports it, that is a deployment decision recorded with its own provenance —
the design carries it, the design does not assert it is sanctioned.

**Credentials are pooled, not singular.** A subscription has windowed quota and an API key has rate
limits, so the unit is a pool with per-member state — `available`, `rate_limited(until)`, `rejected`,
`disabled` — rotating **least-recently-exhausted**, never round-robin, because round-robin burns every
window together and takes capacity from full to zero with no warning. A rotated retry is the *same*
logical call and must reuse its charge identity; the ledger already had to distinguish a re-run from
an auto-retry, and rotation is the second kind. Pool exhaustion is a first-class refusal naming when
capacity returns, in the shape of `CAP_REACHED`, not a crash and not a retry storm.

**Consequence, foreseen by ADR-025.** A subscription-authenticated run has no per-token price, so it
charges `pricing_mode = UNMETERED` — an asserted zero, never a coerced one — and the money cap is
inert for it. ADR-025 wrote this down in advance: *"a money-denominated cap will be inert by design
on an `UNMETERED` deployment, so the eventual cap needs a token- or call-count axis regardless of
pricing mode."* The call-count axis becomes the live control, joined by a third: quota headroom.

**Consequence, unforeseen.** OpenAI states in its own terms that generated code may carry third-party
licences. A factory merges such code at volume, so licence provenance is a real pipeline concern
(NFR-F10) rather than a footnote.

**Re-check before shipping each arm.** Terms for automated use changed twice during 2026. Every
finding above carries its retrieval date for exactly that reason.

---

## ADR-029 — Harnesses are driven, not built, and the harness is a runtime registry row

**Context.** The factory needs an agentic tool loop: read, search, edit, run a command, observe,
repeat, with context management and compaction. Code Spire's `LlmProvider` is a single-shot call and
is not that. The build-or-drive question had to be answered before anything else, because it decides
the module structure.

**Decision.** Drive an existing harness behind a `HarnessAdapter` seam. Do not build a tool loop.

**Why.** A survey of open-source harnesses finds the same four tools in all of them — read, search,
edit, bash — which makes the loop look easy, and the same survey measures **20–30 percentage points
of benchmark difference on the same model, attributable to harness quality alone.** The difficulty is
not the loop; it is context management, compaction, tool-result shaping and the thousand small
decisions that separate a working harness from a demonstration. Building that is a second product.

**The harness is a runtime registry selection, not a build-time dependency.** Anthropic's January 2026
restriction landed server-side with no warning. A design that welds one harness into the worker is one
vendor policy change away from a dead product; a registry with several arms survives it. This is the
same reasoning that made SCM and LLM providers registry rows rather than configuration.

**Order: Codex first, pi second.** Codex is Apache-2.0, has a dedicated non-interactive subcommand
emitting newline-delimited JSON with an honest exit code, ships OS-level sandbox modes with network
off by default, reaches any OpenAI-compatible endpoint through `model_providers`, and is the path
OpenAI's own documentation describes for automation. pi is MIT, provider-agnostic through the existing
LLM registry, and the only candidate with a bidirectional session mode — which is what makes **steer**
implementable. Building the second arm is what turns the seam from an assertion into a fact, per the
existing rule that a capability parity supported only by a grep is not done.

**Two contract rules that are not obvious.** `usage()` returning empty means **UNKNOWN, never zero** —
ADR-023 exists because four separate places turned unknown into zero, and a harness whose usage shape
is unrecognised must arrive unpriceable rather than free. And `command()` returns argv, never a shell
string, because a prompt is untrusted text from a tracker.

**Not adopted: the Unified Harness Protocol.** An Apache-2.0 open standard exists covering the same
harnesses, with a versioned specification and a conformance suite. It is an HTTP hop and a
hosted-product-shaped API, where `spire-run-worker` already owns the process directly. `HarnessAdapter`
is shaped so a `UhpHarnessAdapter` is one more arm, for an operator who already runs one — the same
posture as a remote-tracker bridge.

---

## ADR-028 — The factory extends the kernel; a run is a workload beside the review, not a second control plane

**Context.** Extending Code Spire from a reviewer into a software factory admits three shapes. Extend
the existing kernel with one new deployable and new sagas. Build a separate control plane that drives
Code Spire over HTTP — the pattern the closest prior art chose. Or extend `spire-review-worker` to
also run agents.

**Decision.** Extend the kernel. One new deployable, `spire-run-worker`; new deciders and sagas in
the existing orchestrator; factory commands and events on the Kafka bus that already exists, into the
event store that already exists, surfaced in the UI that already exists, charged to the ledger that
already exists.

**Why not a separate control plane.** That prior art chose it because its kernel is deliberately
minimal — its philosophy explicitly restricts core to the run loop and pushes everything reactive
outside. Code Spire's kernel is not minimal in that sense and was never meant to be: it already owns
operator auth and RBAC, an encrypted event store, a priced charge ledger with spend caps, an attention
panel and a dashboard. A separate plane would rebuild all five to gain a decoupling nobody has asked
for, and would then need its own credential, its own database and its own UI.

**Why not the review worker.** Three independent reasons, each sufficient. A run holds tens of
gigabytes of disk and an hour of wall clock, while the review worker is a short ordered blocking
consumer. A run needs a Docker socket or Kubernetes API access, and that privilege must not sit in the
process that posts review comments. And one stalled run must not stall every review — this project has
already paid for that exact failure, when a slow model call outran the SmallRye ack threshold and
dead-lettered a consumer that re-stalled on every restart.

**The guaranteed output is a pushed workspace branch.** Opening a pull request, updating a tracker,
merging and closing are policy layered on that boundary, not part of the kernel. Adopted from the
prior art, and it is what keeps the run plane free of product decisions.

**The tracker stays the source of truth.** No issues table, no issues UI, no sync. Code Spire stores
only its own run bookkeeping, keyed by `(work source, repository, issue id)`. Every customer already
has a tracker, and owning a second means owning drift, permissions and reconciliation forever.

**Provider neutrality is build-enforced from the first commit.** `spire-arch` today fails the build
when a core module names an SCM or context provider outside a reasoned allowlist; it is extended to
harness, runtime and work-source names **in the same commit as the first seam**, not after the first
leak. The SCM version of that check found three real leaks, one of which was a live defect returning
500 where it should have returned 404.

**Deliberately out of scope:** campaign controllers, multi-repository policy execution, learned
routing and replica dispatch. Each is recorded in `docs/factory/ROADMAP.md` with the price of
admission that would schedule it.

---

## ADR-027 — Findings are retained as a queryable projection, and a learned preference filters visibly

**Context.** P4 was scheduled on the assumption that a corpus of accepted and rejected findings
existed to learn from. Reading the schema against the documents found it did not, in two layers.
`DATA-MODEL.md` had specified `review_finding` — *"persisted for dashboard / analytics / memory"* —
since the beginning, and no migration ever created it; ADR-026 and `V5__code_symbol.sql` both cited
it as an existing precedent for the coordinates-clear/content-encrypted split. And the findings
themselves were being discarded continuously: the persisted domain stream carries
`ReviewOutcomeRecorded(commit, findingsCount, summaryDigest)` — a count and a digest — while the
findings ride inline on integration events under ADR-014's short bus retention, and
`review_status.posted_findings_json` holds one overwritten round. That is ADR-011 working exactly as
designed. Nobody had drawn the consequence.

**Decision.** `review_finding` (V36) is the durable record: one row per finding per round,
coordinates and classification in clear because the table exists to be grouped server-side, message
and suggestion Tink-encrypted because they quote the source under review. Analytics (FR-11) ships
with it. Learned memory (FR-10) proposes preferences an operator approves, and an approved
preference **filters after generation** rather than steering the prompt.

**No backfill, and the corpus therefore starts empty.** A salvage from `posted_findings_json` would
give exactly one unrepresentative round per review with no verdicts — rows that look like history
while being a single snapshot. The same honest shape as ADR-026's symbol index, and it means FR-10
cannot be measured on the day it ships; that is written into the exit criteria rather than
discovered later.

**Analytics ships with the projection, not after it.** It is the only way to tell a correct
projection from a wrong one, because a bad number is visible immediately and a bad row is not —
ADR-023's sequence, where building the ledger and reading it back is what exposed four separate
places that had turned *unknown* into *zero*.

**The filter is deterministic and counted, because a silent mechanism is one this project has twice
paid for.** Prompt injection might produce better findings rather than merely fewer, but nothing can
tell whether the model honoured the instruction, and a finding it silently skipped leaves no trace.
The LLM circuit breaker once recorded a failed future as a success while looking installed, and
ADR-023's `0` meant *unknown*. A filter that records what it removed cannot fail that way: the count
is on the summary comment and the dashboard, the hidden findings are one click away, the rows stay
in the corpus, and revocation takes effect on the next review.

**Three write rules are not the obvious ones**, and each obvious version fails silently.

- *Verdicts do not judge the previous round.* `priorRun` is built from the carried-forward OPEN set
  (V20), which spans every earlier round, so a finding raised in round 1 and fixed in round 4 still
  has its row at round 1. A `round - 1` rule updates round 3, matches nothing, and throws nothing.
  Matching is the newest not-yet-judged row for the location across all rounds, preferring the
  verdict's own thread ref — recency by row order, never id arithmetic (the V26 lesson).
- *Thread-ref attachment is not scoped to a round.* A push between generation and posting appends a
  new `ReviewRequested`, so the current round has already moved and refs would attach to a round that
  generated nothing.
- *Idempotency is delete-then-insert, not a unique key.* A redelivered `ReviewGenerated` re-runs the
  handler inside the `isReviewing` window — the window the V30 double-charge lived in. A unique key
  cannot help: `category` is nullable by design and Postgres treats NULLs as distinct, so it would
  fail on exactly the uncategorized rows, while simultaneously dropping two legitimate findings of
  one category on one line.

**Per-author analytics is self-visible, and its identity link is never inferred.** An operator sees
their own numbers, an admin sees anyone's. That needs `operator_identity` (V37), keyed on
`(oidc_subject, provider_type)` — the platform is not decoration, since the same `providerUserId` on
two SCMs is two unrelated people, the collision this project has been bitten by twice. Matching
usernames automatically is **refused rather than deferred**: a coincidental match shows one person
another person's performance data and nothing in the UI looks wrong. The per-author read is
row-level authorized in code because `@RolesAllowed` cannot express "a viewer may read their own
row", and an unmapped operator is refused, never defaulted. `/api/me` grows a `subject` field
because nothing else lists operators, so an admin would otherwise be asked to type a value nobody
can see.

**Consequences.**

- `Finding` gains a nullable, closed `category`. Closed because free-text categories from a model
  produce a long tail of near-duplicate labels that group nothing, and grouping is the point.
  Nullable because a customized `REVIEW` template (E16) never asks for it; those rows degrade to
  severity-and-path grouping. An unrecognised label parses to **null, not `OTHER`** — `OTHER` is an
  answer the model gave, an unparseable label is unknown, and ADR-023 is the standing lesson.
- Conversation-raised findings and customized-prompt repositories are structurally inert for learned
  memory (no category, and for the former no message either). The Memory screen says so rather than
  showing an empty list that reads as "nothing to learn yet".
- Stale runs are not recorded — this projection sits inside `ifCurrentRun`. `chargeGeneratedCalls`
  deliberately does not, because the money was spent, so a repository's spend can include runs that
  contributed no finding rows and the two lenses will not reconcile exactly.
- The per-repo spend lens inherits `techdebt/spire-orchestrator/3-3`: `llm_charge` keys on a
  `reviewId` carrying no provider, so one workspace name registered on two SCMs sums two unrelated
  repositories. Cited where the figure renders rather than left to be rediscovered.
- No retention on `review_finding` in this milestone; a purge rides with the ADR-024 purge when that
  exists, which now has a second table to cover.
- `projection_checkpoint` remains dead schema. A generic event-log replay would not have recovered
  historical findings anyway, since the log never carried them.

**Rejected.** *Backfilling from `posted_findings_json`* — one unrepresentative round per review.
*Deriving categories from message text* — messages are encrypted, and clusters shift whenever the
model or prompt changes, making a preference unstable for reasons no operator could see. *Extending
`review_thread` instead* — it is per posted thread, so it misses everything never posted, and its
rows alias across rounds, which is what caused the ADR-019 reconciliation defect on GitLab.
*Username-matching for identity.* *A composite per-author quality score* — mostly a function of what
someone was assigned. *Writing findings to the domain event log* — a reversal of ADR-011's
minimize-stored-source posture, needing its own decision.

---

## ADR-026 — The repository knowledge base is derived, structural, and confirmed at citation

**Context.** ROADMAP records P3 as "whole-repo RAG": embeddings, a pluggable vector store, and a
push-triggered incremental indexer, with `ARCHITECTURE.md` §5 offering it as the worked example of a
capability added with zero core change. Reading that plan against the shipped code found the
technique had been named before the need was. P3's own stated exit criterion — *reviews reference
code elsewhere in the repo, not just the diff* — is met by resolving the definitions a diff depends
on, which needs no vector store at all.

**Only similarity search needs the apparatus, and it buys the weakest findings.** Of the four things
whole-repo context could give a reviewer — definitions of what the diff touches, similar code
elsewhere, call-site impact, and inferred conventions — only *similar code elsewhere* is a similarity
problem. Definitions and callers are deterministic lookups; conventions are a one-shot summarization
whose right delivery vehicle is a draft `.codespire` a human merges, not runtime retrieval. Similarity
is also the only one of the four that simultaneously requires stored source, a cold crawl, staleness
management, and embedding spend that has no `call_ref` scheme under ADR-023 and sits outside every
cap ADR-025 built.

**The repository size range rules out any crawl-based design.** Target repositories span roughly ten
files to ten thousand. A crawl cannot complete inside the context aggregator's twenty-second budget
at the large end and is pointless at the small end, so no single crawl-based design serves both.
What survives the whole range is the option whose cost scales with the **diff** rather than the
repository: a three-file pull request is a three-file pull request in either. The small end then
needs no special case — import closure from the diff reaches most of a ten-file repository anyway, so
near-total coverage falls out of the same mechanism, with no size threshold, no second code path, and
no operator-visible cliff where behaviour changes.

**A push-fed index is strictly worse than a review-time one, and breaks an invariant to be so.**
CONTRACT §9 states every topic is keyed by `reviewId` and calls the keying discipline the important
invariant; a push carries no `reviewId`, so `PushReceived` -> `RepositoryIndexDecider` would introduce
the first non-`reviewId` message class in the system. It would also be *less* correct: the index's
only reader is a review, and a review-time refresh keys the index to the exact commit under review,
which a push-fed index cannot guarantee because it can lag the pull request head. The attention panel
faced the same pressure and refused for the same reason — most of what it needed was state, not
events, so each service answered for its own schema over a surface it already had.

**Decision.** The repository knowledge base is built in two rungs, neither of which crawls, embeds,
or consumes `PushReceived`.

1. **Rung 1 stores nothing.** At diff-fetch, `DiffWorker` derives changed paths and the identifiers
   appearing in changed lines. A new Apache-2.0 `spire-context-code` provider resolves those against
   the changed file's own import block — read at the review commit — and contributes
   `ContextItem{kind=CODE_SNIPPET}` through the existing `ContextProvider` SPI. For import-based
   languages the repository already contains a hand-written, compiler-verified dependency graph;
   reading it costs one file fetch and a parse, and is precise rather than probabilistic. Java and
   TypeScript ship first; `LanguageSupport` makes a further language a bean, not a core edit.
2. **Rung 2 stores structure, never content.** `worker.code_symbol` records `(repo, symbol, path,
   role)` for every file a review reads, growing toward the part of the codebase that is actively
   changing. It never crawls, so a ten-thousand-file monorepo never holds most of itself, and that is
   correct rather than a limitation. Rung 2 is gated on rung 1 clearing an evidence bar fixed in
   advance.

   **Outcome (2026-08-29): the bar was not cleared, rung 2 is not authorized, and P3 closed at rung
   1.** The measurement ran on five merged pull requests plus one from the test repository, each
   reviewed twice with only the code-context provider toggled: 10 findings shared, 7 only with
   context, 8 only without — against a noise floor, measured by running the identical arm twice, of
   five differing findings on a pull request where nothing changed. The toggle moved findings no more
   than rerunning the same configuration did.

   **Recorded deliberately: the null is corpus-limited, and does NOT mean the feature was falsified.**
   The runs produced 3 code findings against 15 documentation findings, because this repository's
   large pull requests are majority ADRs, runbooks and plans — and code context can only ever change
   a code finding. There were three, in both arms. A future reader deciding whether to reopen rung 2
   should know the gate established that *this corpus cannot measure it*, not that the retrieved
   definitions failed to help; an ADR that records a verdict without its reason converts a corpus
   limitation into a false fact about the product. Reopening needs a majority-code corpus with
   cross-file dependencies, and the noise-floor run the criterion originally lacked.

   **Reopened and built on operator judgement, 2026-08-29.** Rung 2 ships without the gate having
   been cleared. That is a deliberate override, recorded here rather than left as a contradiction
   between this ADR and the code: the gate is a discipline the operator adopted and may set aside,
   and the reason given was that the feature can be verified on this repository afterwards.

   That reasoning is sounder than the gate's own framing allowed for. Rung 1's value needed an
   operator to judge whether findings improved — subjective, and swamped by model variance at the
   corpus sizes available. Rung 2's core claim is not subjective: `callersOf` either returns
   the files that reference a symbol in this repository or it does not, and confirmation-at-citation
   either drops a stale candidate or it does not. Both are facts, checkable without an LLM. The
   evidence bar for rung 2 is therefore a different instrument from the one rung 1 failed, and this
   ADR should not be read as rung 2 having evaded a test it could have passed.

   What remains genuinely unproven is the downstream claim — that a cited caller makes a review
   better. That is the same question the corpus could not answer for rung 1, and building rung 2 does
   not answer it either.

**The index is a hint, never an answer — and that is what removes the staleness problem.** Nothing is
cited from the table. Candidate paths are re-fetched at the review commit and the reference confirmed
before it becomes a snippet. There is therefore no invalidation pass, no indexed-commit versus
review-commit reconciliation, and no path by which a stale row produces a finding about code that no
longer exists, because every citation was read moments before it was cited. An index that only
narrows the search may be arbitrarily wrong and stay safe; an index that answers the question must be
kept correct forever. Same rows, same table — the difference is entirely in what they are allowed to
authorize. `last_seen_commit` is therefore diagnostic and pruning metadata only, and any read that
compares it against the review commit has reintroduced the design this rejects.

**Structure is stored; content never is, so ADR-011 stands unamended.** ADR-011's no-diff-persistence
decision rests on minimizing stored source as a liability, and a knowledge base is the obvious place
that erodes. It does not here: the table holds identifiers and paths, and snippet text is fetched live
and discarded. Storing them unencrypted is consistent rather than expedient — encrypted columns cannot
be queried server-side, and `review_finding` and `review_thread` already store `path`/`line` in clear
for exactly that reason while their messages are encrypted. Coordinates are queryable; content is
encrypted. The residual is recorded in SECURITY.md rather than left implicit, and it is larger than any
single row suggests: individually a symbol name leaks domain vocabulary, but the table accumulates an
identifier inventory and a file-level dependency map across every repository the deployment reviews —
the shape of the codebase without a line of its source. The trust boundary is the operator's own
Postgres, the one that already holds their findings and paths, and the levers are
`SPIRE_SYMBOL_INDEX_ENABLED=false` (degrades to rung 1, which stores nothing) and the retention window.

**Code context gets its own prompt slot.** `{{context}}` is four thousand tokens shared by tickets,
pages, issues and rules, concatenated in list order and clipped from the tail, so which context
survives is decided by arbitrary ordering. Snippets placed there would be silently truncated away on
precisely the repositories with the richest context — tests green, review normal, the only trace a
`truncated` boolean nobody reads, which is the same silent-success shape as a circuit breaker
recording a failed future as a success. `{{code_context}}` with its own budget makes eviction
impossible rather than unlikely, mirroring `prior_findings`, which has its own slot for the same
reason.

**Consequences.**

- `ARCHITECTURE.md` §5 step 3 is **removed, not deferred**. `PushReceived` stays declared and
  unemitted; no new topic, no non-`reviewId` message class, and no webhook re-registration by
  operators. The section's "zero core change" claim survives for *contribution* and is corrected for
  *acquisition*, which is honestly not free.
- Adding a palette variable changes the built-in default templates, so an operator with a customized
  template silently gets no code context. The mitigation already exists: the default-drift banner
  (V33) is built to surface exactly this, and a test asserts it fires for a template lacking the new
  slot.
- The `SymbolIndex` port is defined in rung 1 although rung 1 never reads it. ADR-021 forbids the
  Apache-2.0 provider depending on the FSL worker that owns the schema, so the port must exist for
  rung 2 to be an addition rather than a refactor — the `BlobStore` arrangement, repeated.
- Code references travel on their own wire field, not the neutral `references` set, and snippets are
  excluded from the aggregator's level-2 reference mining. Otherwise a `PROJ-123` in a code comment is
  fetched as a Jira ticket, and hundreds of identifiers are scanned by every registered provider — a
  set documented as recall-favouring on the grounds that a false candidate costs nothing, which holds
  at ticket-key volume and not at symbol volume.
- **A deliberate, reasoned exception to ADR-011, not an unamended invariant.** Identifiers harvested
  from a diff's changed lines are diff-derived tokens, and they ride the Kafka bus on
  `DiffFetched.codeReferences` — `WorkerPipelineTest.fetchDiffEmitsMetadataOnly` had to be narrowed to
  accommodate them, so this narrows something the guard previously asserted. It is accepted because
  `DiffFetched` already carries `repoRules` — the entire text of the repository's `.codespire` file,
  whenever one exists — so the bus already carries whole-file content by design; a set of identifiers,
  by contrast, cannot reconstruct a diff. The line the rung 2 schema itself holds — structure stored,
  content never — is unaffected and needs no exception of its own.
- Partial recall must be worded as partial. A finding claiming "all three callers" when twelve exist
  is fabrication, so snippets carry "known callers" framing and the prompt forbids claiming
  exhaustiveness over a handed set.
- **Every rung-2 loop is bounded, and each bound costs recall only.** Citations (3), index reads per
  review (8), confirmation fetches per review (20), rows per file per role, files recorded per review
  (100), and a per-review wall-clock deadline shared with the fetch phase. The three that matter most
  are the least obvious: the confirmation budget bounds the work *between* an index read and a citation,
  which neither of the other two touches — a common identifier could otherwise spend one content GET per
  candidate against the SCM rate limit every adapter shares; the per-review file cap is what makes the
  per-file row cap mean anything, since a thousand-file pull request would otherwise write the per-file
  cap a thousand times in one pass; and caller snippets are trimmed against the same `MAX_SNIPPETS`
  total as definitions, because that number is derived from the `{{code_context}}` slot's token budget
  and appending to a full list overflows the very slot the cap exists to protect — silently, by
  tail-clipping, dropping the callers just appended.
- **A confirmed caller is re-recorded.** The write phase can only record what has been fetched by the
  time it runs, which never includes a caller — a caller is fetched *to confirm it*, afterwards. So the
  rows this feature depends on most, the ones a review has just proved still correct, kept the timestamp
  of whichever review first saw them and were the first the retention sweep deleted.
- **`SPIRE_SYMBOL_INDEX_ENABLED` turns rung 2 off without turning code context off.** Off passes a null
  index, which is rung 1 exactly and already tested — this is the feature's first persistent store, it
  is unencrypted by necessity, and it shipped on an override rather than the gate, so an operator needs
  a lever short of redeploying.
- If similarity search is ever wanted, it becomes a column on `code_symbol` rather than a new
  subsystem.

**Rejected.** *Embeddings and a vector store now* — the technique the roadmap named, deferred until a
consistency-gap class of miss is demonstrated that definitions and callers do not cover. *Resolving
the Qdrant-versus-pgvector contradiction* between ROADMAP and DATA-MODEL — this design needs neither,
so the contradiction is marked unresolved rather than settled by a decision nothing yet depends on; if
similarity is ever scheduled, pgvector wins on the one-Postgres grounds ADR-011 already states. *A
size threshold* switching between derived and persistent modes — two code paths, two failure modes,
and a cliff an operator can see. *Trusting the index* — every design in this space is fragile at
exactly the point it lets a stored row speak for current code.

---

## ADR-025 — Spend caps refuse before the call, and a refused review is `refused`, not `failed`

**Context.** ADR-023 rebuilt LLM cost as a priced charge-line ledger precisely so that a fleet spend
cap could be built on numbers that mean something. Nothing then read that ledger back. The bounds
that did exist sound aggregate and are not: `maxTokens` caps one call's output, `SPIRE_REVIEW_MAX_ATTEMPTS`
is the ADR-016 auto-retry budget rather than a spend budget, the conversation turn cap is per
**thread**, and the diff token clip truncates a huge pull request instead of declining it. So a
deployment could spend without limit, and "this diff is too large to be worth reviewing" was a
judgement nobody was allowed to make.

**The unbounded path was the conversation, and the codebase already said so.** `ConversationSaga.planFollowUp`
emitted a paid `AnswerFollowUp` guarded only by `DefaultLlm.isSpendable()` — *can this model be
priced*, a credential question rather than a budget one. Threads cost nothing to open, the turn cap
bounds a single thread, and `CallRefs`' own javadoc states the rest outright: *"the default turn cap
is 4, and an @-mention removes the cap entirely, so the loss was unbounded."* The comment immediately
above that guard records that ADR-023 argued this path safe by construction and that V30 falsified it.
The first draft of this design repeated the same assumption and gated only the `/review` path: it
would have closed the front door and left the window up, in the one file that already explains why not
to.

**Decision.** Three gates, each sitting where its inputs already are, all speaking one refusal
vocabulary — `CapRefusal`, a reason plus a timeline `detail()` and an operator-facing `note()`,
modelled on `DefaultLlm` and deliberately *not* folded into `DefaultLlm.Refusal`, which answers "can
this LLM be used"; merging them would drag budget policy into credential resolution, two concerns that
change for different reasons.

| Gate | Site | Refuses on |
|---|---|---|
| Diff size | `ResultSaga`, on `DiffFetched` | changed files / diff bytes |
| Pre-spend | `ResultSaga`, before `GenerateReview` | window spend / call count |
| Conversation | `ConversationSaga.planFollowUp` | window spend / call count |

The diff gate has to be on `DiffFetched`: `changedFiles`, `sizeBytes` and `truncated` live on that
event and **nowhere afterwards**, so checking later would need new wire fields or new columns — and it
would first run the whole context fan-out (per-issue API calls, a bounded 20-second wait, an encrypted
blob write) only to discard the result. The conversation gate sits **after** the @-mention override
rather than beside it: the override must keep bypassing the *turn* cap and must not also bypass the
*spend* cap, since that combination is what made the path unbounded. It also sits after the free
`NotifyTurnCap`, which buys nothing and whose silence this project has already paid for once.

Limits live in `app_setting`, surfaced under Settings → General. **Every limit is optional, and unset
means unlimited** — a deployment that configures nothing behaves exactly as it does today. A non-null
default would silently change a running deployment's behaviour on upgrade, which is the mistake V30
made by leaving legacy models rateless and still the most operator-visible consequence of ADR-023. An
unparseable stored value reads as unset for the same reason, failing open: a deployment that silently
stops reviewing looks like an outage, while a wrong-looking field in Settings looks like exactly what
it is. The window is the one setting with an effective default (a day), because a rolling window with
no length is not a weaker cap but a meaningless one.

**A refused review reaches a terminal state, and that state is `refused`.** The pre-spend refusal
these gates are modelled on (`skipUnspendable`) writes a note and nothing else, so the review sits in
`reviewing` until `REVIEW_STUCK` fires blaming *"a webhook delivery path or a worker"* —
`AttentionQueries`' own javadoc records this — and, since ADR-024's archive guard refuses any row
still `reviewing`, such a review cannot even be cleared. That is tolerable for `MODEL_NOT_PRICEABLE`,
a configuration error an operator fixes once. It is unacceptable for a cap that refuses **routinely
and by design**. So `ResultSaga.refuse` mirrors `onReviewFailed`'s terminal shape: clear the scheduled
retry, record the timeline line, set status and note, and drive the aggregate terminal with
`RecordFailure(retryable=false)`. `setError` is deliberately not called — there is no provider or
worker error, and populating it would make the detail page show an infrastructure fault for a policy
decision.

`refused` rather than `failed` because status is load-bearing: the archive guard, the attention
queries and the reviews-list filters all key on it. Filing a deliberate refusal as an infrastructure
failure would put it in the same bucket as a genuine outage and make every routine refusal raise a
`REVIEW_FAILED` row telling the operator to re-run it. This is the same split that took `pr_state` out
of `status` — a merged PR and a passed review are different facts one badge could not carry, and so
are a refusal and a failure.

**And a projection was quietly overwriting it.** `refuse` routes through `RecordFailure` →
`ReviewFailedTerminally`, and `DomainEventSink` projected that event as `status = 'failed'`
unconditionally — relabelling the refusal one Kafka round trip after the saga wrote it, while the note
stayed correct. The aggregate deliberately keeps **one** terminal-failure event; a second would change
the wire contract for a distinction only the read model draws. `refused` is therefore a read-model
*refinement* of `ReviewFailedTerminally`, and `projectTerminalFailure` declines to coarsen it
(`WHERE … lower(status) <> 'refused'`). Worth recording because of how the defect presents: every
saga-level test passed, since only a test reading the status *after* the event round trip can see it
at all.

**Every cap carries a call axis, not only money.** ADR-023 established that a money-denominated cap is
**inert by design on an `UNMETERED` deployment**, where every charge is a legitimate asserted zero and
`SUM(cost_millicents)` never approaches a positive limit. `SpendWindow` therefore reports two figures
over the window: `SUM(cost_millicents)` and `COUNT(DISTINCT call_ref)` — one per *LLM call*, so a
review, its reconcile and each follow-up count separately, which is also what makes the call axis the
thing that genuinely bounds the conversation path. The pair closes the ADR-023 hole exactly: an
`UNKNOWN`-priced row carries a NULL cost that `SUM` skips, and the call count catches it anyway.

**The spend read must NOT filter `archived_at`.** Ten ledger reads beside it do, and every one of them
is right to — they answer *"what does this review's page show"*. This one answers *"what has already
been spent"*, and money spent is not un-spent by a row being tidied away. Copying the filter here
would make archiving a way to hand budget back, so anyone who can clear a review could defeat the cap.
It is called out because the surrounding code establishes the opposite pattern, which makes this the
single most likely way the feature regresses.

**One `SpendGate`, not one comparison per site.** The pre-spend gate, the conversation gate and the
attention row all ask the same question and all get their answer from the same bean. Two copies would
be free to drift — one gaining an axis, one keeping `>` where the other has `>=` — and drift in a
money gate is invisible until it fails to fire. Sharing the decision makes "every site reaches the
same verdict from the same inputs" true by construction rather than by test.

**The cap is soft, and this ADR says so.** Charges are recorded after a call completes, so every
review already in flight passes the pre-spend check before any of their charges land. Overshoot is
bounded by *in-flight reviews × per-review cost* and is not eliminable without a reservation protocol,
which is disproportionate here. Stating it is the point: a cap documented as exact and then observed
to overshoot destroys trust in the number, whereas a cap documented as soft with a bounded overshoot
is simply true.

> **The bound moved on 2026-08-28.** `DEFAULT_MAX_OUTPUT_TOKENS` went 4096 → 16384, because at 4096 a
> reasoning model spent its whole budget thinking and returned nothing on a real diff. That default is
> the fallback for every provider whose operator has not set a per-model `max_tokens`, so the *per-review
> cost* term above rose by up to 4× on the output axis — typically the more expensive one. The cap is no
> less soft than it was and nothing about the mechanism changed, but the number a deployment can
> overshoot by is larger, and a bound stated once and then quietly moved is the failure this ADR
> already warns about. An operator who wants the old ceiling sets `max_tokens` on their LLM provider.

**Rolling window, not a fixed bucket.** A fixed bucket's only advantage is predictability, and it is a
false one. With charge timestamps in the ledger, the earliest instant capacity *can* return is
computable — oldest in-window charge plus the window length — so the attention row can name it
(*"No capacity returns before …"*), which is strictly more precise than *"resets at the top of the
hour"*. The bucket keeps its boundary-gaming weakness (twice the limit across two adjacent buckets) and
buys nothing in exchange.

**That instant is a lower bound, and the row must word it as one.** An earlier draft of this ADR
claimed the *exact* instant capacity returns was computable, and the row said *"Capacity returns at
…"*. It does not follow. Ageing the oldest charge out restores enough capacity only when usage exceeds
the cap by no more than that one charge's contribution: with a call cap of 100 and 300 calls in the
window, the named instant takes usage to 299 and changes nothing; on the money axis a $10 cap with $50
spent and an oldest charge of $0.004 names an instant that recovers 0.04% of the overshoot. The exact
version needs a walk over charges oldest-first, accumulating until the remainder falls under the cap.
The honest wording costs nothing and this ADR already argues the principle two paragraphs above — a cap
documented as exact and then observed to overshoot destroys trust in the number. Neither the test nor
the runbook could catch it: both were written with a cap of 1 and one call in the window, the single
configuration in which the exact claim is true.

Naming that instant needs a second read, so `SpendWindow` gained `oldestChargeAt(Instant)` beyond the
two figures `Usage` carries. It belongs there rather than as a third ad-hoc `llm_charge` query inside
`AttentionQueries`, because one place reading that ledger is the whole point of the type — and it
carries **no `archived_at` filter** for exactly the reason `since` does not: a purged review's money
still occupied the window while it was live, and the instant capacity returns does not move because a
row was tidied away. `Usage` deliberately does not grow a timestamp field; it answers "how much", and
one record answering both would put a value in it that only one caller ever reads.

**The `CAP_REACHED` row is `BLOCKING`, not `WARNING`.** A cap doing exactly what it was told is not a
fault, and the first instinct is to file it below a real problem. That reads severity as blame.
Severity here describes **impact**: while the cap holds, every subsequent paid call is refused at all
three gates, so the operator-visible effect is the same "nothing will run" shape as
`LLM_DEFAULT_MISSING` or `MODEL_NOT_PRICEABLE`, and it should sit beside them rather than below them.
The usual objection to a blocking row — that it nags — does not apply, because this one self-clears
when the window rolls or the limit is raised.

**Consequences.**
- **Refusals are loud, and never posted to the pull request.** Each writes a timeline entry, sets the
  review's note, moves the review to `refused`, and raises a `CAP_REACHED` attention row. Nothing is
  commented at the SCM: the `/review` refusal path already established that replying confirms to a
  prober that the command is wired, and costs an API call per probe.
- **`CAP_REACHED` carries no acknowledgement watermark**, unlike ADR-023's two cost rows. Those
  describe calls already made that no fix can un-make; this describes usage *right now*, so it clears
  by itself when the charges age out of the window or the operator raises the limit — the attention
  panel's contract that fixing the cause removes the row.
- **A refused follow-up does not move the review.** The review may have completed, and declining one
  reply is not a retraction of that outcome; `CapRefusal.note()` opens *"Not reviewed"*, which on a
  reviewed PR is simply false. The conversation gate records the decision on the timeline **and on the
  review's own event log** (`FollowUpRefused`, filed under the conversation root) and leaves status and
  note alone — the one gate that deliberately does not call `refuse(...)`. The persisted event is not
  optional decoration: the timeline is an in-memory list capped at 500 entries *deployment-wide* and
  lost on restart, so without it the only durable trace of an unanswered reply is the deployment-wide
  `CAP_REACHED` row, which never says which thread went quiet.
- **Refusal text names the measurement, never the configured cap.** `detail()` and `note()` reach three
  surfaces a `spire-viewer` can read — the timeline, the review's note and the attention row — and
  ADR-022's third rule makes configuration admin-only *including its reads*. The measured figure is this
  review's own context and stays; the limit is rendered only by `logDetail()`, for the log, and by
  `GET /api/settings/caps`, which is admin. The precedent is in this repository: ADR-023's review round
  dropped per-token rates from `ReviewDetail` for exactly this reason.
- **Failing open is silent unless it says so.** `SpendWindow.since` answers "allow" when its own ledger
  read fails, which is right — a cap that refuses every review because its query failed is an outage
  that looks like policy. It originally answered `Usage(0, 0)`, making an unreadable ledger
  indistinguishable from an empty one, so the attention panel reported health while nothing was being
  enforced; the plausible trigger is connection-pool exhaustion under load, i.e. the exact burst the cap
  exists to bound. The read now answers `Optional.empty()`, `SpendGate.Decision` carries
  `ledgerUnreadable` beside the verdict, and a `CAP_UNENFORCEABLE` row (WARNING — reviews still run)
  states the condition where the panel promises it will.
- **This branch added a backend status and the dashboard silently mapped it to success.** A fact about
  the seam rather than about the feature, and worth stating because the next status will meet it too:
  `spire-ui`'s `ReviewStatus` is a **compile-time union over runtime JSON**, so `tsc` checks the literal
  set while the value arrives unvalidated from this service. `refused` therefore could not break the UI
  build, could not fail a test, and fell through each consumer's default branch — which for the progress
  bar meant five green segments under *"done"*. A refusal presented as a success is worse than the
  silence this ADR's own gates are designed to avoid, because silence at least looks like nothing
  happened. Fixed for `refused`; tracked as a class in `techdebt/spire-ui/3-3-…`, since the same seam
  covers every other union in `api.ts` that mirrors a backend vocabulary. Note that the two vocabularies
  are already known to differ: `ReviewState.Status` holds the aggregate's five values, while the read
  model writes lower-case strings and has grown `superseded`, `observed` and now `refused` on top.
- **A failed ledger read fails open.** `SpendWindow` answers zero usage and logs at ERROR: a cap that
  refuses every review because its own query failed is an outage that looks like policy, while a cap
  that misses a window is a bounded overshoot. When neither limit is configured the read is skipped
  entirely, so unset is a true no-op rather than a query that always answers "allow".
- **The caps are deployment-wide, not per repository.** `llm_charge` carries no `provider_type`, which
  `techdebt/spire-orchestrator/3-3-…` already records; a per-repo spend cap inherits that entry's
  collision — one workspace name registered on two SCMs sharing a budget — and belongs with it.
- **Spec B, the per-repo admission rate limit, is deliberately not built.** It is the only part of the
  feature that needs new storage (a counter table, and the only thing in the design needing pruning),
  so it splits cleanly at the state seam. The spec records what it must carry: two gate sites
  (`IntegrationSaga.onPullRequestEvent` plus `ReviewRerunService.rerun`, which REST reaches directly),
  the gate placed ahead of `lifecycle.handle(RequestReview)` so a refused admission does not start a
  run anyway, a counter keyed on `(provider_type, workspace, slug)`, no re-counting when the retry
  scheduler re-dispatches, no counting of observe-mode registrations, and a decision about a
  first-contact PR that has no `review_status` row for a note to land on.

**Rejected.** Estimating cost before the call — refusing on a *predicted* price needs a token estimate
the system cannot validate, the fabricated-number problem ADR-023 exists to prevent; these gates refuse
on measured history and measured input only. Queuing or deferring a refused review — a backlog would
itself need bounding, and a refused review is refused, not held. Per-actor limits — per-repo bounds
total volume regardless of who, and per-actor isolation is real for multi-tenant but speculative at
three repositories. Reusing `failed` for refusals, and extending `DefaultLlm.Refusal` with the cap
reasons: both covered above, and both amount to letting one field carry two facts.

---

## ADR-024 — Deleting a review archives it; recorded LLM spend is never destroyed

**Context.** `ReviewProjection.deleteReview` was a true hard delete. One transaction removed the review
row, its scoped timeline, the underlying event stream, the worker's idempotency claims, its context
blob — and, as of ADR-023's review round, **its charge ledger**. That last one destroys real, paid
usage: a review deleted for being clutter took its token counts, its model and its cost with it, and no
query could reconstruct them. On the dev deployment a single delete removed four charge lines and
11,454 millicents of genuine spend.

That contradicts the principle ADR-023 was built on. Snapshotting `rate_millicents_per_million` onto
each charge line exists so **a later price edit cannot rewrite history** — reproducibility by
construction rather than by convention. It made cost immune to a *price* change and left the same
history fully exposed to a *different* mechanism: a button whose whole purpose is tidying the list.
A ledger that is authoritative about the past has to survive the housekeeping of the present, and one
that a routine UI action can erase is authoritative only until someone tidies up.

**Decision.** Deleting a review **archives** it. `review_status.archived_at` (migration V32) marks the
review — `NULL` means live — and nothing is deleted: not the timeline, not `event_log`, not the
worker's claims or context blob, and above all not `llm_charge`. `DELETE /api/reviews/{ws}/{slug}/{pr}`
becomes `POST …/archive`, with `POST …/unarchive` to reverse it, because a `DELETE` verb that destroys
nothing misdescribes the operation to every future reader of the API. The reviews list defaults to live
rows, with **Show archived** including archived rows inline, visually marked; an archived review keeps
a fully working detail page.

**This reverses part of ADR-023's own review fix, and that is safe for a structural reason rather than
a judgement call.** The `llm_charge` deletion closed a real defect: `review_id` is
`ReviewIds.reviewId(repo, pr)` — stable per PR, not per run — and `llm_charge.review_id` is plain
`TEXT` with no foreign key, so orphaned rows were not merely unreachable. `costOf` / `listSummaries` /
`latestModelFor` key on `review_id` alone, so a **re-registered PR rendered the deleted run's money and
model as its own**, and the new run's `call_ref` collided with the orphan, discarding a real charge.
Every step of that hazard requires the review row to be **gone**, so the PR can be registered afresh.
Archiving keeps the row, and the archived PR is retired, so there is no second review that could
inherit anything. The hazard is closed by keeping the row rather than by destroying the evidence — the
same outcome, reached without paying for it in history.

**The clean slate the deletion defended was never complete anyway.** `review_thread` is deleted
nowhere in the codebase, so a re-registered PR already inherited stale thread rows. This is worth
recording rather than quietly relying on, because it changes how much the old behaviour could be
trusted: the property being protected did not hold before this ADR either, so choosing retention over
it gives up less than the argument for deletion implied.

**Retirement is a spend boundary — and specifically NOT what makes retention safe.** An archived PR is
retired: no push, `/command`, reply, close, re-run or scheduled retry starts work on it again. The
first draft of this design justified retirement as the thing that keeps the retained ledger honest, and
that reasoning was **false**. With nothing deleted, a resurrected PR's old charges are genuinely its
own history, and `ReviewRuns.currentRun` — which counts `ReviewRequested` rows in the review's own
retained `event_log` — stays correct across a resurrection. Retention needs no retirement at all.

The correction is recorded rather than silently replaced because the false reason would have collapsed
under the first person who checked it, and it would have taken the decision down with it: a reader who
disproves the stated rationale reasonably concludes the behaviour is unnecessary and removes it. The
real reason is narrower and holds: **an author pushing a commit must not silently re-bill an operator
who archived the review to be done with it.** Retirement is a cost control, and the gate belongs where
money is spent. It also keeps `review_status`'s `PRIMARY KEY (review_id)` intact, since a per-run
review identity would mean changing the aggregate stream id — an event-store contract.

Six paths enforce it, because no single choke point sees them all: four integration events
(`AuthorReplied`, `ManualCommandReceived`, `PullRequestEventReceived`, `PullRequestClosed`) in
`IntegrationSaga`, plus `ReviewRerunService` and `ManualRegisterResource`, which are REST and never
reach the saga. `PullRequestClosed` is the one that would otherwise pass unnoticed: it writes
`pr_state`, so without it an archived review's badge would still move on the next merge.

**Charges are stamped at purge, not at archive.** `llm_charge.archived_at` exists, and every ledger
read filters it, but **archiving never writes it** — only a future purge will, in the same transaction
that hard-deletes the review row. Stamping at archive was the first draft and was self-defeating: the
per-review cost reads are keyed by `review_id` alone and are *the same reads that serve the archived
review's own detail page* (`loadDetail` → `chargeLines(reviewId)` + `costOf(reviewId)`) and its
Show-archived list row. Filtering them would have shown an archived review a cost of zero and no model
— contradicting the entire purpose of retaining the data, in the one place an operator would go to look
at it. Stamping at purge gives every property wanted at once: an archived review keeps its own cost
visible, a purged review's orphans stay off the PR that later inherits its `review_id`, and statistics
ignore the filter and see everything ever spent.

**Archived is a third dimension, not a status value.** `review_status` already carries two orthogonal
facts: `status` (the run's outcome — `completed`, `failed`) and `pr_state` (`OPEN`/`MERGED`/`CLOSED`),
split apart in July 2026 because a merged PR and a passed review are different facts one badge could
not carry. Archival is a third, and overwriting `status` with it would destroy whether the run
completed or failed — precisely the statistic the data is being retained for. An archived review still
reports the outcome and the finding count it had at the moment it was archived.

**Consequences.**
- **Archiving a running review is refused** (`ArchiveOutcome.STILL_RUNNING`). `ResultSaga.ifCurrentRun`
  guards on commit alone, so an in-flight worker's results would still write status, findings and
  charges to a row this ADR promises is frozen — and those late charges would carry a `NULL`
  `archived_at` into a purge, becoming exactly the orphan the column exists to prevent.
- **`archiveReview` returns a four-valued enum, not a boolean.** The `UPDATE`'s `WHERE` matches zero
  rows for all three failure cases, so a boolean could not tell "no such review" from "already
  archived" from "still running", and each needs a different answer to the operator (404 / 409 / 409
  with distinct text).
- **Retirement is answered, not silent.** An inbound reply, `/command` or PR update on an archived
  review posts a one-time notice (`NotifyArchived` → `ArchivedNotified`, fixed text, no LLM
  credential). A close gates without notifying: it is not a human asking a question, and the notice
  fires once *ever*, so spending it there would leave whoever later asks a real question with silence.
  This project already learned once, from the conversation turn cap, that an unexplained non-response
  reads as a lost webhook.
- **Once per review means a second person replying in a *different* thread gets silence.**
  `NotifyTurnCap` made the opposite call — one claim per thread — for exactly that reason. Chosen
  deliberately here, and recorded so the trade-off is visible if it proves wrong in use.
- **The purge itself is not built.** `llm_charge.archived_at` and the ten filtered ledger reads are its
  groundwork, landed now because the day the purge is written is the day a re-registered PR starts
  reporting a dead review's money as its own. When it is built it must stamp the charges in the same
  transaction that deletes the review row.
- **Already hard-deleted reviews are not backfilled.** Their rows are gone.

**Rejected.** A parallel archive schema — every future migration would have to mirror into it or drift
silently, "all reviews ever" would become a `UNION`, and `event_log` is the append-only source of truth
`JdbcEventStore.load` rehydrates aggregates from, so relocating its rows mutates the event store rather
than archiving a projection. A read-only `live_review` view — archived reviews must be visible in the
same table behind a toggle, which a permanently-excluding view fights. Unarchive by manual SQL: since
reversing an archive is one `UPDATE` (no charge rows need unstamping, per the stamp-at-purge decision),
the alternative was never "no unarchive" but "unarchive as database surgery".

---

## ADR-023 — LLM cost is a charge-line ledger with snapshotted rates, and zero is a category

**Context.** The pre-existing accounting turned *unknown* into *zero* in four places, each individually
defensible: a blank price field defaulted to `0` in the UI, a `0` rate passed REST validation (which
rejected only negatives), a registry coercion turned a missing rate into `0L`, and a `SQLException`
during pricing lookup answered `0L` rather than propagating. None of the four was a bug in isolation —
each layer's own logic was correct for what it alone could see. The result was a stored cost that could
mean "this call was free," "the operator forgot to enter a price," or "the database blipped for a
second," with nothing in the row to tell those apart. A fleet spend cap built on that ledger would
install cleanly, look correct, and never fire for exactly the calls it exists to stop — the same failure
shape as the LLM circuit breaker once recording a failed future as a success (debt wave 2): a control
that is present, inert and trusted. Fixing the accounting had to come before the caps, not alongside them.

**Decision.** Cost becomes a **charge-line ledger** (`llm_charge`, migration V30): one row per token
type per call, priced at the rate in force when the call happened, with a `pricing_mode` of `METERED`,
`UNMETERED` or `UNKNOWN` recorded on the row itself rather than inferred from the number.

**Why the rate travels with the charge instead of being re-derived from a catalog.** The alternative —
keep prices in `llm_model`/`llm_model_rate` and join on `priced_at` — was considered and rejected as a
**temporal price catalog**. It makes every statistics read an interval join, it leaves "what did we
actually charge" contingent on the catalog's integrity forever (a later `DELETE` or a bad backfill
silently rewrites history), and it does not even solve the case it exists for: an operator who enters a
price *today* still has no recorded price for *yesterday*'s calls, because nothing wrote one at the time.
Snapshotting `rate_millicents_per_million` onto the charge line at write time makes every figure
reproducible as `tokens × rate ÷ 1e6` **by construction**, not by convention — a later price edit is
structurally invisible to history rather than merely discouraged from touching it.

That rate is stored, not served. A review's charge-line payload is viewer-readable and a rate is
operator-entered configuration, which ADR-022 makes admin-only *including its reads*; carrying it on the
review view put one value on both sides of that rule, and `CACHED_INPUT`/`CACHE_WRITE`/`REASONING` rates
had no viewer-visible counterpart to be inferred from the way INPUT/OUTPUT did. So reproducibility is a
property of the **ledger row**, not of the page: the review page shows what a call cost and which world
its model is in, and the rate behind it is on the admin-only Models page and in the row itself. It is also
bounded above (`MAX_RATE_MILLICENTS_PER_MILLION`, ~$10,000 per million tokens) — not a pricing policy but
an overflow guard, since `tokens × rate` in `long` millicents wraps into a **negative** cost past roughly
9.2e12, and a negative charge subtracts from every total it lands in while raising none of the attention
rows an unpriced call does. V31 adds the matching `CHECK`.

**Why `pricing_mode` is a category, not a stricter number check.** The blank-becomes-zero defect
survives any single-layer fix, because the layers disagree about what a bare `0` *means* and each is
right about its own slice: the UI's default is a reasonable convenience, the REST layer's "reject
negatives" is a reasonable bound, and the registry's null-coercion is a reasonable fallback for a column
that used to be `NOT NULL`. No amount of tightening the *number* check closes the gap, because both "this
model is free" and "nobody told us the price" arrive as the same `0`. `pricing_mode` removes the
ambiguity at the source: `METERED` requires a rate `> 0` for `INPUT` and `OUTPUT` (the two dimensions
every vendor reports on every call) and rejects `0` outright; `UNMETERED` is the operator's explicit
assertion that inference is free, and carries no rates at all. A model saved without stating which world
it is in cannot exist.

**The token partition, cross-checked per vendor.** `spire-llm`'s `TokenUsageMapper` maps each vendor's
usage object onto a neutral `TokenType` partition (`INPUT`/`CACHED_INPUT`/`CACHE_WRITE`/`OUTPUT`/
`REASONING`), asserting `Σ(per-type tokens) == totalTokenCount()` — arithmetic the vendor computed
independently, which is what makes the check meaningful rather than circular. **The cross-check is
per-vendor, not uniform**, because the vendors disagree about what their own total covers: OpenAI's and
Gemini's cached-token counts are a *subset* of the headline input count and get subtracted out; Anthropic's
cache reads and writes are *additional* line items, and — verified against the LangChain4j builder, which
has no setter for it — **Anthropic's own `totalTokenCount()` is derived as `input + output` and excludes
both cache buckets entirely**. Comparing every bucket against the vendor total, as an earlier draft of
this design did, would have made every *cached* Anthropic call fail reconciliation and degrade to a
single unpriceable `TOTAL` line — the cheap calls being the only calls we could not price, exactly
backwards from what a cost ledger is for. A vendor whose usage is entirely absent or that reports no
tokens still writes one `TOTAL` line at `tokens = 0`, because a call that happened must be countable even
when the vendor reported nothing — `SUM` over zero rows is indistinguishable from a call that never
occurred.

**The priceable-model rule is enforced twice, and neither guard may be collapsed.** Pricing is
**post-hoc**: `ResultSaga` prices a call only after `ReviewGenerated` returns, by which point the LLM
spend has already happened. That ordering is what forces the guards to split across three places instead
of living in one:

- **Config time** (`LlmProviderRegistry.create`/`update`) — a provider naming a model the catalog cannot
  price is refused before it can be saved, so the bad configuration cannot exist. This guard was added
  late, in a follow-up task, after a review observed the rule was enforced only at `LlmProviderResource`
  — its one existing caller — rather than at the invariant's actual boundary. A choreography test that
  registered a provider through the registry directly, bypassing the REST edge, proved the gap live: an
  uncatalogued model reached the pipeline and could only be caught downstream.
- **Pre-spend** (`ResultSaga`, immediately before emitting `GenerateReview`) — because config-time can
  only stop a *save*, not a model an operator deleted or renamed out from under a provider after the
  fact, and because it is the last point at which an unpriceable review can still be **refused** rather
  than merely reported. Skips with a dashboard note, mirroring the existing credential-missing skip.
- **Post-hoc** (`LlmModelPricer.pricingFor`) — the backstop for everything upstream missed: a lookup
  failure, a redelivery racing a mid-flight catalog edit, or (before this task) a rename. Records
  `pricing_mode = 'UNKNOWN'` and raises attention. **Never** fabricates a price, and never fails a review
  whose money is already spent.

Collapsing either of the first two loses something distinct: dropping the registry guard means a caller
that bypasses the one resource that has it — a seeder, an import, a future second write path — can still
create an unpriceable provider; dropping the saga's pre-spend check means the only remaining guard runs
*after* the money is gone, which is reporting, not prevention. The registry guard also does the same
double duty on the **model** side: `LlmModelRegistry.delete` already refused to remove a catalogued model
a provider still names, but `update` had no equivalent — renaming a model orphaned every provider naming
the old value exactly as deleting it would, and was the one path that could still defeat the config-time
guard after it had passed. `update` now refuses a rename while any provider references the current name,
reusing the same in-use count and message as `delete`.

**The conversation path is guarded too, and the argument that it needn't be was wrong.** This ADR
originally held that `ConversationSaga` could emit `AnswerFollowUp` — a paid call — with no priceability
check of its own, because the registry guard makes an unpriceable provider *impossible to create*, and
because a silent pre-spend skip would reintroduce the failure shape the `NotifyTurnCap` notice exists to
remove: the bot going quiet in a thread a human is actively watching. The second half of that still
holds. **The first half is falsified by V30 itself**: the migration leaves every legacy zero-priced model
rateless by writing SQL directly, so the unpriceable state arrives without passing through the registry
guard at all. On an upgraded deployment new reviews were correctly refused while an author replying to a
live thread still made the bot spend — up to the turn cap, or unbounded once it is @-mentioned, which is
deliberately cap-exempt. A transient `SQLException` in `pricingFor` produces the same asymmetry.

So the guard is applied on that path as well, and the answer to "would it go quiet?" is *no*: the skip
records a timeline note naming the model and a dashboard note naming the fix, in the same shape as the
sibling missing-credential skip. It posts nothing into the thread, unlike the turn cap — the turn cap is
a hand-off with nothing further to come, where this is a misconfiguration whose fix makes the next reply
work. And rather than leave the check duplicated at two emit sites, "resolve the default credential" and
"confirm it can be priced" became one answer (`WorkerLlmCredentials.resolveDefault` → `DefaultLlm`): a
caller cannot take the credential without being told, so the *next* emit site is guarded by construction
instead of by remembering. The conversation path still records its cost honestly either way —
`ChargeKind.FOLLOWUP` lines land in the same ledger, priced or `UNKNOWN` like any other call.

**Renaming a catalogued model was the other hole in the original argument**: a rename could orphan a
provider the conversation path was already trusting. It is refused now — the registry guard has to hold
on *both* the provider side and the model side — but it is no longer the only thing standing between an
unpriceable model and a paid follow-up.

**`UNIQUE (call_ref, token_type)` — the double-charge fix.** The method this replaced was an unguarded
`INSERT` with a fresh `UUID` and no uniqueness, protected only by `ResultSaga.ifCurrentRun`'s staleness
check — `isReviewing() && commit == currentCommit`. Between `ReviewGenerated` and `ReviewCompleted` the
review is still reviewing at the same commit, so a redelivered result passed that check and inserted a
second row for a call that happened once. Today that only inflates a dashboard figure; under a spend cap
built on this ledger it would corrupt the control. The constraint makes the illegal state
unrepresentable at the storage layer rather than merely discouraged in the service that writes it.

**Superseded in part by ADR-024.** This ADR's review round also made `deleteReview` clear `llm_charge` alongside the review's other rows,
reasoning that "delete is a true clean slate" so a re-registered PR could not inherit an orphaned run's
money. That reasoning no longer describes the system: **there is no hard delete.** Archiving keeps the
review row, which retires the PR and so removes the second review that could have inherited anything —
closing the same hazard without erasing the ledger this ADR exists to make trustworthy. The
`archived_at` filters on the ledger reads remain, for a purge that does not exist yet.

**What this ADR does not claim.** `ModelUsage` is a Kafka wire type and this branch reshaped it in
place — same name, new components, the money field removed so a worker adapter cannot express a cost
even by accident. The ADR-013 contract-compat snapshot gate stayed green through that change, but **it
did not catch it and could not have**: `ContractSchemaSnapshotTest` renders each record component as
`name: TypeName` and never recurses, so the golden file records `usage:
dev.codespire.contract.review.ModelUsage` and nothing about that type's own shape — reshaping it is
invisible to the gate by construction. The break is safe because `DomainEvent` carries no usage field at
all (verified directly, not assumed) so the event store is untouched, and because in-flight Kafka
messages live under short retention (ADR-014) — **not** because a compatibility check approved it.
Crediting a gate that did not run is the kind of claim a future reader would rely on and shouldn't. Filed
as `techdebt/spire-contract/3-2-contract-snapshot-does-not-recurse-into-nested-wire-types.md`, since the
same blind spot covers every other nested wire type in the contract.

**Consequences.**
- **One legacy dataset, not preserved.** `review_llm_call` is dropped rather than migrated: every `0` in
  it is ambiguous between "was unpriced at the time" and "was free," and the distinguishing information
  was never recorded, so no migration could recover it honestly. `llm_model`'s rates migrate only where
  unambiguous — a rate `> 0` can only have been operator-entered, since the old coercion produced `0` —
  so a model with any zero rate is left without rates and the new guards then treat it as unpriceable.
  **One-time operator action:** any model saved with a zero rate must be given real rates or marked
  `UNMETERED` in Settings → Models before it will run another review. This is the guard working as
  specified, not a regression.
- `review_status.model` / `tokens_in` / `tokens_out` / `cost_millicents` are dropped with no replacement
  column — they were a rollup of the ledger being replaced, and the ledger itself is now the source of
  truth for a review's total, a call's breakdown, or a fleet aggregate, all as the same `SUM` over
  `llm_charge` with a different `WHERE`.
- **Fleet cost/abuse caps** (`docs/ROADMAP.md`, "Explicitly deferred") now have a ledger honest enough to
  build a cap on — see `docs/SECURITY.md` for the consequence that carries forward: a money cap is inert
  by design on an `UNMETERED` deployment, so the caps will need a token- or call-count axis regardless.

**Rejected.** A temporal price catalog (above). A stricter numeric validator in place of `pricing_mode`
(above — no number check distinguishes the two meanings of `0`). Cascading a model rename into every
referencing `llm_provider.model` automatically, instead of refusing the rename: a silent cascade changes
provider configuration the operator did not touch, where refusing and naming the fix leaves the operator
in control of which side moves.

---

## ADR-022 — Operator auth is a cookie session per service, not a bearer token

**Context.** SECURITY.md specified the shape years before it was built: *"auth-code + PKCE at the UI;
JWT bearer validated per request against the issuer's JWKS."* That is the modern default, and for a
plain REST API it would be right. Implementing it (D10) surfaced a constraint the original design did
not account for: **the dashboard is not a plain REST API — four of its live surfaces are WebSockets**,
and a browser cannot set an `Authorization` header on a WebSocket handshake. The alternatives are a
credential in a query string (which lands in access logs, and the project forbids it) or the
`Sec-WebSocket-Protocol` smuggle. Cookies, by contrast, are sent on the handshake automatically.

**Decision.** `quarkus.oidc.application-type=hybrid`: a **cookie session** for the browser and its
sockets, **bearer** for everything else. Each of the three services is its own OIDC client with its
own cookie name and its own `cookie-path`, and each service's browser-facing surface was moved under
a prefix of its own — the orchestrator to `/api` (sockets included, at `/api/ws/*`), the gateway to
`/gw`, the worker to `/wk`.

**Why the prefixes are load-bearing, not tidying.** Cookies are scoped by **host + path**, not by
backend. All three services sit behind one browser origin, so while the gateway's API was nested at
`/api/webhook-repos` the browser sent the *orchestrator's* session cookie to the gateway on every
call, and the proxy forwarded it. Per-service encryption secrets do not help: the encrypted cookie
**is** the credential, so a compromised gateway could replay it. Path scoping is what actually stops
the credential arriving. Measured: the session cookie now reaches its own prefix and nothing else.

**What hybrid buys beyond the sockets.** It preserves the bearer half of the original design, so
`curl`, CI and the SMOKE-TEST runbook still authenticate the way SECURITY.md intended. The departure
is therefore narrower than a pure cookie design — the ID token path is added, not substituted.

**Consequences.**
- **CSRF becomes a live concern** where bearer tokens were immune to it. The session cookie is
  `SameSite=Lax` (measured), and no `GET` mutates — verified across all 21 resources.
- **Sessions expire in five minutes** by default, so socket close-and-reconnect is the ordinary path
  rather than an edge case. Both hooks ask *why* a socket closed before retrying; a blind retry
  hammered the identity provider on every routine expiry.
- **A same-origin residual remains.** Path scoping stops a compromised gateway *receiving* the
  orchestrator's credential; it does not stop one that achieves script execution in the shared origin
  from using it. `HttpOnly` prevents reading a cookie, not using it. Recorded in SECURITY.md.
- **Three silent redirects on first load** — one per service — traded for the isolation above.
- The realm must define an **audience mapper per client**, because pinning `token.audience` without
  one fails login outright with `No Audience (aud) claim present`.

**Rejected: terminate auth at the orchestrator and proxy to the others.** `/api/webhook-repos` carries
plaintext webhook secrets on write; proxying them would route those through the one service holding
the master keyset and the event store, destroying the boundary the gateway exists to create.

---

## ADR-021 — Split licensing: Apache-2.0 libraries, FSL-1.1-ALv2 services

**Context.** ADR-006 chose Apache-2.0 before the first commit, for a project with no commercial
intent. Two things have changed. The deployables now contain the actual engineering value — the
event-driven pipeline, ADR-019 reconciliation, the conversation loop, the reconcile/verdict model —
none of which existed when the licence was picked. And a hosted offering is now a stated *possible*
future (PRD §6), which Apache-2.0 gives away in advance to anyone who gets there first.

Two facts made this the cheapest possible moment to decide: every commit is by a single copyright
holder (274/274), so no consent round is needed; and the repo had 0 forks, so nothing of consequence
continues under the old grant. Both properties are perishable — the first ends at the first merged
outside PR, the second at adoption.

**Decision.** License per module rather than per repository:

- **Apache-2.0** — `spire-contract`, `spire-diff`, `spire-encryption`, `spire-scm-*`,
  `spire-context-*`, `spire-llm`, `spire-arch`. The plugin SPI and every reference adapter.
- **FSL-1.1-ALv2** — `spire-gateway`, `spire-orchestrator`, `spire-review-worker`, `spire-ui`.
  The runnable services.

The map and the adopter-facing explanation live in `LICENSING.md`.

**Why FSL rather than BSL 1.1.** Both restrict competing commercial use. BSL's default template
forbids *production* use outright and requires a hand-written Additional Use Grant to re-permit it —
the clause every BSL adopter has had to author, and the one most likely to be got wrong. It also
needs a Change Date stamped per release, four years out, which drifts the moment releases are cut by
hand. FSL is that grant pre-written: production and internal commercial use are named Permitted
Purposes, the Apache-2.0 conversion is granted irrevocably up front at two years, and there is no
per-release bookkeeping. Same protection, half the restriction period, no parameters to misconfigure.

**Why split rather than relicense wholesale.** Design pillar #2 — *add a capability without touching
the core* — is only credible if the thing you extend is genuinely open. Restricting the SPI would
make the plugin promise hollow while protecting nothing a competitor couldn't rewrite in a week: a
Bitbucket HTTP client is not the product. `spire-diff` additionally credits PR-Agent as prior art
(see `NOTICE`), which belongs on a permissive licence on principle. The line is "libraries you
compile against are open; the product you run is not."

**On the tension with ADR-007.** ADR-007 rejected KurrentDB partly because ESLv2 is "explicitly NOT
OSI open source" and hard-depending on it is "a strategic liability and off-putting to contributors."
That objection was about an unavoidable third-party *dependency* whose vendor could tighten terms
later and who gates features behind a paid key. It does not transfer wholesale to licensing our own
code: adopters take no third-party lock-in they cannot see, the surface they extend stays Apache-2.0,
every feature ships in the source with nothing behind a key, and each version converts to Apache-2.0
automatically after two years — ESLv2 offers no such conversion. What *does* carry over is the
contributor-friction cost. That is accepted, and partly bought back by keeping the SPI and all
reference adapters permissive. ADR-007's own decision — Postgres over any source-available engine —
is unchanged.

**Consequences.**
- Code Spire is **source-available, not open source**. Docs, README and PRD say so; claiming
  otherwise would be false.
- Versions published before this change stay Apache-2.0 irrevocably. `v0.1.0-apache` tags the last
  Apache-2.0-only commit of the full tree.
- **No Apache-2.0 module may depend on a service module** — permissive may flow into restrictive,
  never the reverse. The current graph already satisfies this; if a library ever needs something
  from a service, the code moves down into a library.
- Contributions need a sign-off and an explicit relicensing grant (`CONTRIBUTING.md`), or the split
  cannot be maintained later.
- **The name is provisional and the trademark question is therefore deferred, not open.** "Code
  Spire" is a working name; the shipped product may be called something else. Neither licence stops
  a fork from using a name — only a mark does — but registering a mark for a name that will change
  spends money on the wrong asset. Revisit when the name is settled, not before. Nothing in the
  licensing depends on it: the grants run from the copyright holder (a named person), not from the
  project name, so a rename changes branding and not terms. FSL's Trademarks clause reserves the
  name regardless of registration.
- The user-visible name sits in six production literals across four files: `PromptCatalog`'s
  `REVIEW_PERSONA` and `FOLLOWUP_PERSONA`, `ReviewWorker`'s summary header (also asserted in
  `FindingConversation.test.ts`), the bot display name in `FindingConversation.tsx` and `render.tsx`,
  and copy in `PromptsSettings.tsx` — backend *and* UI. Don't spread it further; centralising it into
  one constant is worth doing before a rename rather than during. The internal surface
  (`dev.codespire`, `spire-*`, `SPIRE_*`, docker volumes) is private and need not track a rename.

---

## ADR-020 — No provider-dependent decisions in core, enforced by the build

**Context.** Plugin-first only holds while the shared code makes no provider-dependent
decision, and by the end of the GitLab parity work it no longer did. `CommentsPosted.PostedInline`
carried *both* a comment id and a thread ref with a `threadRefOrCommentId()` accessor, because
GitHub and Bitbucket make a comment its own thread root while GitLab's discussion id differs from
its note's — so "some providers have two ids" had been modelled in the contract, and the core had to
choose which one to use. Keying off the wrong one is exactly what made a GitLab reply
unrecognisable. Two more leaks had accumulated quietly: `ProviderIdentityResolver` branched on
`"bitbucket-cloud".equals(type)` to reach a whoami fallback, and `ManualRegisterResource` caught
`BitbucketApiException` — so a GitHub or GitLab PR that 404'd escaped as a 500 rather than "no such
PR". Each accommodation is individually reasonable; together they make every edit to a shared file a
risk to the other two providers, and the resulting bug appears on one platform, in production.

**Decision.** Core modules (`spire-contract`, `spire-orchestrator`, `spire-review-worker`,
`spire-gateway`) must not name an integration provider — on either plugin axis, the SCM a review runs
on and the context sources it pulls from, since both pose the identical risk. The test is *decisions*,
not vocabulary:

- **Rejected** — branching on a provider, or shared code carrying provider-shaped alternatives it
  must choose between. `threadRefOrCommentId()` was this; so was the `"bitbucket-cloud"` branch.
- **Allowed** — one rule stated in domain terms that every adapter satisfies. "Ownership is keyed by
  the thread a reply arrives under" is satisfied by a comment id on GitHub and a discussion id on
  GitLab, and the core never learns the difference. Where providers genuinely differ in capability,
  the difference becomes a neutral SPI method the adapter overrides —
  `IdentitySource.whoamiOrValidate(workspace)`, where Bitbucket's account-less-token fallback lives
  in the Bitbucket adapter and no caller recognises Bitbucket to get it.
- **Allowed** — the composition roots (`ProviderClients`, `WorkerScmClients`, `PrUrlParsers`), whose
  job *is* selecting an adapter, and `ScmType`, which declares the names. Provider-neutral data may
  carry several attributes (`ScmCredential.botAccountId` + `botUsername`) as long as the *selection*
  happens in a composition root, not in a saga or worker.
- Comments are exempt. A comment cannot make a decision, and recording *why* a neutral design exists
  is knowledge worth keeping.

The rule is enforced, not documented: `spire-arch` fails the build when a core module names a
provider outside an explicit allowlist, each entry carrying its reason. It scans **source text**
rather than bytecode, because the leaks that caused real bugs were string literals
(`"bitbucket-cloud".equals(type)`) that a bytecode rule cannot see. Three guards protect against the
one failure mode nobody would notice — a silent pass: the comment stripper has its own tests, the
scan asserts it reached every core module's sources, and a stale allowlist entry fails so exemptions
cannot quietly become permanent. The scanned tree is a declared Gradle task input, or the check would
report a cached pass after the very change it exists to catch.

**Consequences.** Adding a provider touches its adapter, the composition roots, and nothing else.
A capability difference costs one defaulted SPI method instead of a branch in shared code. The cost
is that a genuine cross-provider difference can no longer be expressed inline where it is noticed —
it has to be pushed to the port or the composition root first, which is the intended friction.

**The check's known limit.** It matches provider *names*, and the costliest leaks carry none. A
follow-up audit along four lenses (id/wire formats, capability assumptions, webhook lifecycle,
contract shape) found six, of which one was a live defect the check passed cleanly: the loc→thread
index chose the current thread for a re-posted finding by comparing thread refs numerically, which
holds for a comment id and not for an opaque discussion id — so ADR-019's reconciliation was inert on
one provider, and the only provider name in the file sat in a comment the scanner strips. Recency is
now the service's own insertion order. The others: the GitLab `position` SHA triple removed from the
contract in favour of a single head commit (the adapter reads the rest itself); `@`-mention syntax
moved from a saga into each ingress, which hands the core a list of mentioned identities; HTTP 406
replaced by `ScmApiException.isDiffTooLarge()`; `CommentSink.updateComment` retargeted from a comment
id to a `ThreadRef`, so one opaque ref serves both questions and the core stops minting thread refs
from comment ids; and the gateway brought into scope. The lesson is that the build check bounds the
blast radius of careless edits but cannot replace review of *semantics* — a provider assumption
stated without naming the provider is invisible to it, and is exactly where the expensive bugs were.

**The context axis.** The same shape existed for context sources: the pipeline called two specific
parsers, and `ticketKeys` + `links` were two source-shaped fields riding through three contract types.
It needed a different mechanism, not just a different port method: extraction runs when the diff is
fetched, *before* any context credential has been brokered, so there is no configured
`ContextProvider` to ask. Hence `ContextReferenceSource` — stateless, credential-free, one
implementation beside each provider — and a single neutral `references` set that each provider narrows
to what it recognises. Adding a context source is now a provider plus an extractor, with no pipeline
edit. `ContextProviderResource` and `ContextKeyValidator` stay exempt on the same grounds as
`ProviderClients`: choosing a provider per type to check or preview it is what a composition root is.

---

## ADR-019 — Re-reviews post deltas, not the full finding set

**Context.** Before this decision, a follow-up commit to an already-reviewed PR triggered the exact
same pipeline as the first review: fetch the full diff, run one review call, post a fresh inline
comment for every finding. Every prior finding got re-raised verbatim (duplicate noise on threads the
author was already discussing), nothing closed automatically when a finding was actually fixed, and
the summary comment piled up as a new post each time instead of reflecting current state.

**Decision.** On a follow-up commit to a PR with a posted prior run, the worker runs **two
claim-guarded LLM calls** instead of one. First, a **reconcile call** (`LLM:reconcile` idempotency
claim) judges each prior finding — prior findings + their best-effort thread transcripts + the
incremental diff since the prior run (new SPI `DiffSource.fetchCompareDiff`, falling back to the full
PR diff when the provider can't compare, e.g. after a force-push) — producing one
`FindingVerdict{RESOLVED|STILL_OPEN|ACKNOWLEDGED|SUPERSEDED}` per finding. Second, the **standard
review call** (`LLM` claim, unchanged prompt) runs with an added "already reported — do not re-report"
exclusion section built from the same prior findings, then a deterministic filter drops any new
finding whose anchor collides with a `STILL_OPEN` verdict (it's already tracked in its own thread).
`PostComments` then acts per verdict: closing verdicts (`RESOLVED`/`ACKNOWLEDGED`/`SUPERSEDED`) try
`CommentSink.resolveThread` first — a human who beat the bot to it (`ALREADY_RESOLVED`) means the
reply is skipped entirely; otherwise (resolved-by-us or `UNSUPPORTED`) a reply always follows.
`STILL_OPEN` never resolves and always replies. Genuinely new findings post fresh inline comments, and
the summary is rewritten **in place** (`CommentSink.updateComment`, falling back to a fresh post if the
old comment vanished or was edited away). Every reply/resolve holds its own `comment_idempotency` claim
(`reply:<threadRef>`, `resolve:<threadRef>` — value `bot`/`human`/`unsupported` — so redelivery repeats
zero external calls).

Prior-run state is **command-carried**, not worker-owned: the orchestrator packs `PriorRun{headCommit,
summaryCommentId, findings}` onto `GenerateReview` from `review_status.posted_findings_json`, a
snapshot stamped at the last `CommentsPosted` behind a **commit-match guard** — the snapshot UPDATE
only applies when the posted run's commit still matches the review's current `commit_sha`, so a stale
or superseded run's `CommentsPosted` (reachable only through the worker's head-re-check race) can't
overwrite a consistent snapshot with mismatched findings. This keeps the single-writer aggregate side
(the orchestrator, which already owns `review_status`) as the sole owner of "what was actually posted,"
and the worker stateless across runs — exactly the shape ADR-015 established for brokered credentials.

**Alternatives rejected:**
- **Worker-local snapshot.** Having the worker persist its own "last posted" table would duplicate the
  orchestrator's read model, invite drift between "what the worker thinks it posted" and what actually
  reached the SCM, and hand the worker write ownership of state that belongs to the read-model owner —
  a schema-purpose violation (ADR-011 schema-per-service is about *ownership*, not just tables).
- **Single combined LLM call.** Asking one call to both reconcile prior findings and generate a fresh
  review multiplexes two different tasks into one prompt: it would require rewriting the
  already-proven review prompt (ADR-002) to also emit verdicts, and one
  malformed section of the response would corrupt both outputs instead of failing independently.
- **Deterministic anchor-only dedup (no LLM).** Comparing old and new anchors can suppress a duplicate
  at the *same* position, but cannot tell a fixed issue from one that merely moved or was reworded —
  no real fix detection, and the heuristic would be throwaway work once genuine reconciliation is built.

**Consequences.**
- Every follow-up review now pays for **two LLM claims** (`LLM:reconcile` + `LLM`) instead of one; both
  are claim-guarded so a redelivered `GenerateReview` never re-spends. A first review (no prior posted
  run — `priorRun` null) is untouched: the exclusion/verdict machinery never engages, and every
  extended wire type defaults empty/null via old-arity convenience constructors.
- Bitbucket Cloud has no thread-resolve API for PR comments — `resolveThread`'s default `UNSUPPORTED`
  degrades it to reply-only, so a closing verdict there gets a reply but the thread stays visibly
  "open" in Bitbucket's UI. GitHub (GraphQL `resolveReviewThread`) and GitLab (discussion `PUT`) get
  real resolution.
- Findings untouched by the follow-up (verdict `UNCHANGED`, with a deterministic path-based downgrade
  of `STILL_OPEN` when the incremental diff is available) stay silent on the SCM — the reviewer only
  speaks in threads the author's changes actually affect.
- A follow-up commit that renames or moves a file is followed, not lost: prior findings are remapped
  through the incremental diff's renames before either LLM call, so the reconcile prompt, the review
  call's exclusion list, and the emitted verdicts all carry the finding's NEW path — closing the gap
  where a rename used to re-report the same issue as "new" at the new path while the stale old-path
  entry sat un-reconciled. The read model's carry-forward baseline likewise takes a matched verdict's
  (fresher) path/line over the prior finding's, so the next round's `PriorRun` also has the new path.

**Baseline carry-forward (refinement).** The initial cut stamped `posted_findings_json` as a verbatim
copy of `findings_json`, which holds only the current round's NEW findings — so a still-open prior
finding dropped out of the baseline after one round and the round after re-discovered it as "new,"
posting a duplicate thread. The fix: `recordOpenFindings` now writes a separate `open_findings_json`
baseline that unions this round's new findings with every prior finding whose verdict is
`STILL_OPEN`/`UNCHANGED` (or has no matching verdict at all — carried rather than dropped, the safer
default), keeping each carried finding's original `threadRef`; `recordPosted` snapshots
`COALESCE(open_findings_json, findings_json)` so the baseline carries forward indefinitely while a
pre-refinement row still falls back to the old behavior. The reconciliation view shown on the
dashboard (`reconciliation_json`) is likewise now a **merge-upsert**, not a wholesale replace: each
round's verdicts overwrite only the matching earlier entry (keyed by `threadRef`, else `loc`), so a
finding resolved in round 1 stays visible (as "resolved") in round 2's view instead of disappearing.

`recordPosted` is no longer `posted_findings_json`'s sole writer. A finding raised via `/finding`
(`addConversationFinding`) amends it too, adding just that one finding to whatever is currently
posted rather than overwriting the column, and only when `last_posted_commit IS NOT NULL` — writing
one for a review that has never been posted would invent a snapshot nothing ever actually posted.
The finding already lives on a real SCM thread the moment it is filed, so it is already "posted" in
every sense that column exists to capture; amending in place (rather than copying
`open_findings_json` over it) keeps `recordPosted`'s own `WHERE commit_sha = ?` guard meaningful —
that guard exists precisely so a superseded run's newer, still-unposted baseline can never get paired
with the previous run's `last_posted_commit`, and a blind copy would have defeated it. Both writers
run inside one locked read-modify-write (`SELECT ... FOR UPDATE`) so a `/finding` and a completing
review round for the same reviewId — handled by different sagas off different Kafka topics — cannot
race and silently drop one writer's update.

---

## ADR-018 — LLM provider registry: in-app, encrypted, brokered per command

**Context.** The LLM was selected at worker boot from env (`SPIRE_LLM_PROVIDER` + `SPIRE_LLM_BASE_URL`/
`API_KEY`/`MODEL`) — one provider per deployment, key on disk, no way to change model without a
restart. The SCM side had already solved the same shape: a DB registry (`scm_provider`) with
Tink-encrypted secrets, resolved at review time and brokered encrypted to the worker per command
(ADR-009 + ADR-015). LLM config should follow it, not diverge.

**Decision.** LLM providers are registered in the app (Settings → LLM), stored in `llm_provider`
(Tink-encrypted `api_key`, AAD bound to the row id), never returned by the API. One row is the global
default (partial unique index). At `GenerateReview` time the orchestrator resolves the default,
packs its config as an `LlmCredential`, encrypts it (AAD `worker-llm-cred:<workspace>` — a distinct
prefix from the SCM cred so ciphertexts can't be swapped), and rides it on the command. The worker
decrypts it and builds the model per command (`WorkerLlmProvider`, mirroring `WorkerScmClients`).
The key is validated on save with a cheap authenticated call to the provider's models list,
SSRF-guarded by the shared `PublicHttpsGuard` (the same guard the SCM whoami uses).

`SPIRE_LLM_*` credential env vars are gone. `spire.llm.provider` survives only as a `stub|registry`
mode flag (dev/test stub), like the SCM `spire.scm.stub` toggle. If no default LLM provider is
registered, `GenerateReview` is skipped with a visible note rather than emitted uncredentialed.

**Providers.** Phase 1 supports OpenAI (via LangChain4j `langchain4j-open-ai`). Anthropic and Gemini
are phase 2 — the credential's `type` selects the builder, so they slot in without a wire change. A
per-SCM-provider LLM override (`scm_provider.llm_provider_id`) is phase 3. Subscription-license
backends (ChatGPT/Codex, Claude Code, Copilot seats) are explicitly out: they are not embeddable APIs
and repurposing them violates ToS — use the native provider APIs, which serve the same models.

**Model catalog + cost (roadmap 11).** A separate `llm_model` catalog holds the selectable models and
their token pricing — millicents (1/100,000 dollar) per 1M tokens, integers as providers quote them.
Prices are operator-entered, never hardcoded: model prices drift, and a stale number would make every
cost estimate silently wrong (same reasoning as the no-fabricated-data rule). A provider's model is
picked from this catalog. When a review completes, the orchestrator prices its real token usage
against the catalog (`cost = (tokensIn·inputPrice + tokensOut·outputPrice) / 1M`) and stores
`review_status.cost_millicents` — the field was collected since S1 but always 0. Cost is computed in
the orchestrator (the registry owner), not brokered to the worker, so pricing stays in one place.

**Per-model parameter profile.** Different models accept different request parameters: classic Chat
Completions models take `max_tokens` + a custom `temperature`, while OpenAI reasoning models (o1/o3/
gpt-5) reject `max_tokens` (they require `max_completion_tokens`) and reject a non-default temperature.
Rather than sniff model names in the worker, each catalog model carries a **profile** the operator
declares (`output_token_param` = MAX_TOKENS | MAX_COMPLETION_TOKENS | NONE, `supports_temperature`,
`reasoning_effort`, and a free-form `extra_params` JSON passed through as OpenAI `customParameters` —
the escape hatch for any future param, so a new knob never needs a code change). The profile lives on
the model (it is intrinsic to the model, not the deployment), defaults to the classic dialect so
existing models are unchanged, and is brokered to the worker on the `LlmCredential` (`ModelParamProfile`)
keyed by model name. The worker builds `OpenAiChatRequestParameters` from it — no dialect is hardcoded.
Correctness note: the params ride via the request; the real `OpenAiChatModel` keeps its defaults as the
OpenAI subtype so the merge preserves these fields on the wire (a bare mock `ChatModel` would drop them,
so the mapping is unit-tested directly).

---

## ADR-017 — Self-loop guard in the orchestrator; bot account id lives only in the registry

**Context.** The bot account id exists for exactly one purpose: the self-loop guard (ADR-013) — don't
re-act on comment events the bot itself authored. It used to be threaded everywhere as config: an env
var (`SPIRE_SCM_BITBUCKET_BOT_ACCOUNT_ID`) read by the gateway, and a `botAccountId` field on
`BitbucketCloudConfig`, `GitHubConfig`, and the brokered `ScmCredential` — fed placeholders (`"unset"`,
`"unused-by-worker"`, `"resolving"`) on every path except the gateway ingress. Once provider registration
learned to resolve the id from the token via `whoami()` (the `IdentitySource` port), there were two
sources of the same fact, and the gateway still needed a hand-set env var because — being internet-facing
and least-privilege (it holds no SCM token) — it cannot call `whoami()` itself.

**Decision.** Make the bot account id a **registry-only** fact and run the self-loop guard where the
registry is readable: the **orchestrator**. `IntegrationSaga` drops bot-authored `ManualCommandReceived` /
`AuthorReplied` events by comparing the event's author (which the ingress already carries) against the
workspace provider's `botAccountId` from the registry (whoami-resolved). The gateway ingress stops
dropping and just forwards, holding no identity. `botAccountId` is removed from `BitbucketCloudConfig`,
`GitHubConfig`, and `ScmCredential`, and `SPIRE_SCM_BITBUCKET_BOT_ACCOUNT_ID` is retired.

**Why here, not the gateway.** The gateway can't `whoami` (no token, by design — the internet-facing
service must stay credential-light). The alternatives were worse: give the gateway the App Password
(breaks least-privilege) or have it fetch the id from the orchestrator at boot (a startup coupling for one
string). The orchestrator already resolves the provider per workspace, so the guard costs it nothing new.

**Consequence.**
- One source of truth: the registry. No env var, no placeholder `botAccountId` scattered across configs.
- The guard now runs downstream, so a bot-authored comment event briefly rides `cs.integration` before
  being dropped (vs. dropped at the edge). It surfaces on the timeline as `SelfLoopDropped` — more
  visible, not less. `/command` + replies are P2, so there is no live behavioural change today.
- The gateway holds only the webhook secret (SECURITY.md updated). When a GitHub ingress lands, its
  self-loop guard is already implemented — the same orchestrator check, no new config.

---

## ADR-016 — Bounded auto-retry as a saga-owned budget, not per-call fault tolerance

**Context.** A retryable failure (transient 5xx / I/O / timeout from the SCM or LLM) left a review
**stalled forever**: workers catch the error and emit `ReviewFailed{retryable=true}` instead of
throwing, and the decider's `RecordFailure` branch only produces `ReviewFailedTerminally` when
`!retryable` — so a retryable failure emitted no domain event, issued no next command, and the aggregate
sat in `REVIEWING` with nothing to advance it. Recovery meant a manual re-push. The roadmap framed the
fix as "SmallRye Fault Tolerance retry budgets" (per a `DiffWorker` TODO).

**Decision.** Implement the budget in the **orchestrator's `ResultSaga`, event-driven and persisted**,
rather than as per-call `@Retry` inside the workers. On a retryable `ReviewFailed` with budget left, the
saga bumps a persisted `attempt` counter on the read-model row and **re-emits `FetchDiff`** — restarting
the whole pipeline with a freshly-brokered credential (ADR-015), exactly what a manual re-push does, but
automatic and capped by `spire.review.max-attempts` (default 3). When the budget is spent, the provider
is gone, or the failure is permanent, the saga records `RecordFailure{retryable=false}` so the aggregate
yields `ReviewFailedTerminally` and leaves `REVIEWING`.

**Why saga-level over per-call `@Retry`:**
- **Removes the stall at the actual cause.** The stall is a missing state transition in the orchestrator,
  not a missing wrap around one HTTP call — fixing it where the aggregate lives is the direct fix.
- **Restart-from-`FetchDiff` needs no payload threading.** The failed phase's inputs (`contextRef` for
  generate, the `ReviewResult` for post-comments) are NOT carried on `ReviewFailed`; retrying the exact
  phase would require bloating the failure event or a contract change. Restarting from the diff is
  reconstructable from `reviewId + commit` alone, and the LLM/comment idempotency stores (finding H4)
  make the re-run free of double-charges or duplicate comments.
- **Persisted & crash-safe.** The counter lives in the read model, so the budget survives a worker
  restart; an in-memory `@Retry` loop would reset on every redeploy and couldn't span the pipeline.
- **One retry layer, not two.** Per-call FT nested under a pipeline restart would multiply attempts and
  obscure the true count. A single budget keeps the semantics legible.

**Consequence.**
- No contract change: no new events/commands, `ReviewFailed.attempt` is left informational (the saga
  trusts the persisted counter). New `review_status.attempt` column (V5); `Attempt` on the detail page is
  now live instead of hardcoded `1`. The timeline shows `retry:<phase>`; a transient blip auto-recovers
  without ever showing `failed`.
- The budget is a read-model counter, not a domain fact — a full projection rebuild resets `attempt` to
  1 (a rebuilt-then-failing review simply gets a fresh budget). Accepted: retry budgeting is operational
  metadata, not an invariant of the write model.
- Per-call `@Retry`/`@Timeout` inside a phase (to smooth a single blip without a full pipeline restart)
  remains a possible future refinement layered *under* this budget — not needed to close the stall.

---

## ADR-015 — Active-mode worker credentials: KEK to the worker, credentials brokered on the bus

**Context.** In active mode the review worker performs the credential-bearing work — `FetchDiff`,
`GenerateReview` (re-fetch PR + diff), `PostComments` (fetch + post). Until now it read ONE global
SCM credential from `.env` (`WorkerScmProducer`, a startup singleton keyed to nothing). Phase 2 moved
credentials into the encrypted `scm_provider` registry, but only the **orchestrator** (which holds the
Tink KEK, `SPIRE_ENCRYPTION_KEYSET`) can decrypt it. `SECURITY.md`/ADR-013 deliberately kept the KEK
to exactly two holders (orchestrator + UI), with workers "plaintext-only, no KEK". So there was no path
for the worker to obtain a per-workspace credential from the DB registry. Every `ActionCommand` already
carries `RepoRef repo` (hence `workspace`), but no provider identity or secret.

**Decision.** (1) **The worker joins the KEK holder set.** It reads `SPIRE_ENCRYPTION_KEYSET` and gains
an `EncryptionService`. (2) **Credentials are brokered by the orchestrator over the bus, not resolved by the
worker from the DB.** The orchestrator (sole owner of the provider registry) resolves the provider for a
command's workspace, packs a minimal `ScmCredential` (base URL, auth kind, username, secret, bot account
id), encrypts it with the **master KEK** (AAD bound to the workspace), and stamps the opaque base64
ciphertext onto the three credential-bearing commands. The worker decrypts it and builds a per-command
`DiffSource`/`CommentSink`. A command with no credential (stub/observe/dev) falls back to the stub SCM.

To share the cipher, `EncryptionService` moves into a new **`spire-encryption`** module depended on by
orchestrator + worker (Tink stays encapsulated there).

**Why this mechanic (bus-brokered) over the two alternatives the worker-holds-KEK choice allowed:**
- **vs. worker reads `orchestrator.scm_provider` directly:** rejected — a cross-schema read violates
  ADR-011 (schema-per-service) and ADR-008 (microservices), couples the worker to the orchestrator's
  DB, and duplicates the AAD scheme. Bus-brokering keeps the registry single-owned and the worker
  deployment-independent (works if the two ever split to separate databases).
- **vs. plaintext credential on the bus (ADR-014 infra mitigation):** rejected — a live, directly-
  abusable SCM token is materially more dangerous than the source-quote findings ADR-014 accepted on
  disk. Encrypting with the KEK (which the worker now holds anyway) removes cleartext-at-rest for
  credentials at no extra bootstrap secret.
- **vs. a dedicated worker-only key (envelope distinct from the master KEK):** rejected for v1 as
  over-engineering — it would have kept the master-KEK blast radius unchanged, but the operator chose
  to accept the wider radius in exchange for one keyset and simpler key management. Noted as the
  escalation path if the worker's blast radius ever needs narrowing.

**Consequence.**
- **KEK blast radius widens** from two holders to three (orchestrator, UI, worker). A compromised worker
  now holds the master key that also protects the event log + findings at rest. Accepted by the operator
  for v1; `SECURITY.md` updated to state the worker holds the KEK in active mode and why. The narrowing
  path (dedicated worker key) is recorded above.
- The wire contract gains an opaque `scmCredential` field on `FetchDiff`, `GenerateReview`,
  `PostComments`. `ResultSaga` (which emits the latter two) gains provider resolution; if the provider is
  disabled/removed mid-review the command is skipped with a logged note.
- `.env` SCM credentials are retired for the worker's active path; the worker now needs
  `SPIRE_ENCRYPTION_KEYSET` (the gateway still holds only the webhook secret + bot account id).
- The credential rides `cs.commands` encrypted; short retention (ADR-014) still applies.

---

## ADR-014 — Kafka at-rest posture: no app-layer Tink on the bus (v1)

**Context.** Source-quoting review output (`ReviewGenerated.findings[].message/suggestion`,
`FollowUpGenerated.answerText`) rides **inline** on `cs.results` (ADR-011), and Kafka/Redpanda brokers
persist topic messages to disk for the retention window — so this data rests on the broker in a form
the app does not encrypt. Three stated goals collide: (1) ADR-011 findings-inline; (2) the "source
never rests in cleartext" bar; (3) the KEK blast radius (workers are plaintext-only, no KEK). Only two
can hold as written.

**Decision (v1): accept + infrastructure-encrypt.** App-layer Tink is **not** applied to bus payloads.
The broker boundary is covered instead by:
- **Short retention on `cs.results`** (hours, not days — it is a work queue, not the source of truth;
  the durable copies are the encrypted event log, the encrypted `review_finding` table, and the PR
  itself).
- **Broker disk/volume encryption** (LUKS/cloud-disk encryption on Redpanda/Kafka data dirs) — a
  deployment requirement documented for self-hosters.
- Transport stays SASL/mTLS (SECURITY).

This keeps findings small on the bus (ADR-011 intact) and keeps the KEK held by exactly two services
(ADR-013/SECURITY intact), at the cost of scoping the "never rests in cleartext" guarantee to
**application-managed stores** (Postgres, MinIO) — now stated honestly in SECURITY.md.

**Escalation path** if a stricter threat model ever demands app-layer encryption everywhere: move
findings off the bus behind an encrypted blob ref (reversing ADR-011's inline choice) — option noted,
not taken for v1.

---

## ADR-013 — Operational & distributed-systems guards (correctness before scaffolding)

Resolves edge cases the async / at-least-once design creates. Decided defaults:

- **Ignore bot-authored events (self-loop).** `ScmIngress` drops any webhook whose actor ==
  the bot's own `providerUserId`. Without this, the bot's follow-up comment fires
  `comment_created` and it answers itself forever.
- **Comment idempotency (non-idempotent side effect).** Posting a comment is not idempotent, and
  `consumed_event` dedups *consumption*, not the external effect. Implemented semantics (P1): the
  worker CLAIMS `(reviewId, commit, anchorKey)` before posting and stores the comment id after; a
  row **with** a comment id is final proof-of-post (skipped forever, id reused to reconstruct
  `CommentsPosted` on redelivery); a row **without** one is a crashed claim and is **reclaimable**
  — the retry re-posts rather than silently losing the comment (one duplicate possible only in the
  narrow posted-but-not-marked crash window; at-least-once preferred over loss). Stronger
  reconcile-by-listing-bot-comments remains the escalation if that window ever matters.
- **Stale-run pre-check (not just discard).** Before the expensive `GenerateReview` LLM call and
  before `PostComments`, the worker checks the aggregate's current commit; if the run's commit is no
  longer current, it abandons — **no LLM spend, no stale comment on an old commit**.
- **Cancellation.** Only **PR close/merge/decline** (and operator actions) emit `CancelReview{reviewId}`;
  in-flight workers check cooperatively at each stage boundary (best-effort cancel of the LLM call).
  **Supersede does NOT emit `CancelReview`** — it is fully handled by the workers' stale-run pre-check.
  (A supersede-triggered cancel would be a bug: by the time it reached the aggregate, `currentCommit`
  is already the new commit, so the unconditional REVIEWING→CANCELLED row would kill the new run.)
- **PR closed/merged/declined.** `ScmIngress` translates close events → `PullRequestClosed`; a saga
  cancels any in-flight review and halts further stages.
- **Forced re-review.** `RequestReview{force}` bypasses the reviewed-commit idempotency; a human
  `/review` command sets `force=true`.
- **Head-SHA identity & 12-char expansion.** The idempotency/supersede key uses the provider's head
  identifier **as delivered** (Bitbucket's 12-char short hash is a stable, repo-unique prefix). The
  40-char SHA is expanded **only in the worker** when an outbound API needs it (GitHub `commit_id`) —
  never in the gateway, preserving the "ingress returns 202, no processing" rule.
- **Aggregator timer ownership.** Context aggregation for a `reviewId` is owned by the single
  consumer of that `reviewId`'s Kafka partition; the completeness timeout is a **DB-backed scheduled
  sweep** keyed by `reviewId` (survives rebalance/restart), not an in-memory timer.
- **Retry / resilience budgets** (SmallRye Fault Tolerance) per external-call class: SCM read/write
  10s timeout, 3 retries w/ exp backoff + jitter, circuit breaker; LLM 60s timeout, 1 retry then the
  provider-fallback saga (cost-aware — no retry storms on a paid call); context providers time-boxed
  by the 20s aggregator. Budget exhausted → `cs.dlq` + `ReviewFailed`, surfaced on the dashboard with
  a replay action (FR-8) — "in the DLQ" never means silently dropped.
- **Truncated-diff behavior — never silent.** If `Diff.truncated`/`FilePatch.tooLarge`, the summary
  comment states which files / how many lines were not fully reviewed.
- **Cross-service schema compatibility.** `spire-contract` events get round-trip + snapshot tests in
  CI; a compat-gate fails the build on a breaking change lacking an `eventVersion` bump + upcaster.
  A schema registry is considered post-v1.
- **Bitbucket description quirk.** The gateway forwards the webhook's raw description; the worker
  (already calling Bitbucket for the diff) fetches the authoritative PR resource when the rendered
  markdown description is needed — no extra call in the gateway.

LLM-specific threats (prompt injection, output sanitization, untrusted retrieved content) and
cost/abuse caps are in SECURITY.md.

---

## ADR-012 — Provider-neutral SCM model, verified against 4 real APIs

**Decision:** the canonical SCM value types are a true common denominator across Bitbucket Cloud, GitHub,
GitLab, and Bitbucket DC — verified against their official API docs, not assumed. Key shapes:
- **`Author{providerUserId, username, displayName, email?}`** — key on the stable `providerUserId`
  (never the mutable username); `email` optional (only Bitbucket DC exposes it), never logged/persisted.
- **`DiffRefs{baseSha, startSha, headSha}`** on the PR — GitLab *requires all three* to anchor an inline
  comment; GitHub uses `headSha` as `commit_id`; Bitbucket needs none. Carry all; populate what's given.
- **`DiffLine{type, oldLine, newLine, content}`** — every diff line carries BOTH line numbers; this is
  what lets one `InlineAnchor` map to every provider's anchoring scheme.
- **`ThreadRef` is opaque** — a comment id for Bitbucket/GitHub/DC, a *discussion_id* for GitLab
  (GitLab threads are discussions, not comment chains).
- **`ScmIngress.verifySignature` is per-provider** — HMAC-SHA256 for GitHub/Bitbucket, a constant-time
  static-token compare for GitLab (`X-Gitlab-Token`, not HMAC).

**Why:** inline-comment anchoring + threading diverge hard across providers; modelling top-down from
Bitbucket would have broken GitLab (mandatory SHAs, discussion threading) and needed rework for
GitHub/DC. Verifying first means the plugin-first "any SCM" promise is real, not aspirational.

Full per-provider field mappings + quirks + sources: **SCM-MAPPING.md**.

---

## ADR-011 — Data model: no diff persistence, S3/MinIO for transient blobs, schema-per-service

**Decisions (reviewed):**
1. **Diffs are never persisted** — re-fetched from Bitbucket by `(repo, commit)`; `DiffFetched` carries
   metadata only. Minimizes stored source (a liability) and keeps replay correct.
2. **Object store = S3-compatible (MinIO self-host)** from v1 — not Postgres blobs — to avoid a later
   migration. Holds only **transient assembled-context**, client-side Tink-encrypted, TTL auto-deleted.
   Behind a `BlobStore` port (swappable to AWS S3 / GCS).
3. **One Postgres, schema-per-service** for v1 (logical ownership, simpler ops).
4. **Snapshots deferred** until streams grow.
5. **Findings** ride inline in `ReviewGenerated` (small) and are projected to a `review_finding`
   read-model table (encrypted message/suggestion) — not stored as blobs.
6. **`spire-ui` owns the dashboard read models** (status/thread/finding/event); the context-aggregation
   view lives in `spire-context-worker`. No dedicated projection service.

See DATA-MODEL.md.

---

## ADR-010 — Contract conventions: single-writer aggregate, integration-vs-domain events, reviewId keying

**Decision:** (1) The `ReviewLifecycle` aggregate is the **sole writer** of its domain-event stream;
workers never append to aggregate streams — they emit *integration* result events that sagas translate
into Record commands. (2) Two explicit event kinds: **integration events** (boundary/ingress/worker
results) vs **domain events** (aggregate-sourced source of truth). (3) Everything is keyed by
`reviewId` (= one aggregate per PR) for strict per-PR ordering. (4) The aggregate holds only
decision-relevant state (idempotency + completion); fine-grained progress lives in read models. (5)
Large blobs (diff, findings, context) are stored encrypted and referenced by id; events stay small.

**Why:** single-writer gives clean optimistic concurrency; the integration/domain split keeps the
aggregate pure while allowing async workers; reviewId keying makes ordering trivial; small events keep
Kafka healthy and avoid putting source code on the bus in cleartext. See CONTRACT.md.

**Confirmed facts:** SCM target = **Bitbucket Cloud** (`api.bitbucket.org/2.0`, App Password, signed
webhooks). Local dev = **docker-compose** (Redpanda + Postgres + Keycloak).

---

## ADR-009 — Clean-room, OSS-standard security (no private-code reuse)

**Decision:** Code Spire depends only on public building blocks. The private monorepo
(`encryption-common`, Keycloak config) is **design reference only — zero code copied**.

**Why:** Code Spire is public OSS that strangers self-host; it cannot ship private artifacts (users
don't have them), and copying private code into a public repo relicenses IP and risks leaking secrets
(Keycloak realm exports contain client secrets/redirect URIs). Reuse the *patterns*, not the *code*.

**The stack:**
- **Encryption at rest:** Google Tink (AES-GCM envelope, key ids + rotation), field-level via a JPA
  `AttributeConverter`. **Event payloads are encrypted** because findings/context items may quote source code (diffs themselves are never stored — ADR-011) —
  not just token columns.
- **Human auth:** `quarkus-oidc`, provider-pluggable (Keycloak recommended, not required); auth-code + PKCE.
- **RBAC:** roles `spire-viewer` / `spire-admin` via `@RolesAllowed`.
- **Webhook:** HMAC + source allow-list (machine, no OIDC).
- **Service→service:** OAuth2 client-credentials for REST; Kafka SASL/mTLS for the bus (most traffic).
- **Secrets:** env / K8s Secret / Vault, never in image.

**Later:** if a piece (likely encryption) proves broadly reusable, extract it to its *own* public
library under Apache-2.0 that both the monorepo and Code Spire depend on. See SECURITY.md.

---

## ADR-008 — Microservices (revised; supersedes the earlier modular-monolith call)

**Decision:** Multiple independently-deployable Quarkus services over a **Kafka** event backbone:
`spire-gateway`, `spire-orchestrator`, `spire-review-worker`, `spire-context-worker`,
`spire-indexer` (P3), `spire-ui`, + shared `spire-contract` lib. Kafka is a **v1 dependency**.

**Why (revised):** the earlier modulith call assumed building the platform from scratch. The author
runs a mature Maven microservice platform (Keycloak, gateways, devops) and prefers this topology as
the primary supported deployment — the fixed cost of another service is already paid, and the
event-driven design maps naturally onto per-service choreography.

**Consequences:** a durable broker is required from day one (Kafka/Redpanda; in-memory connector kept
for dev/test). `spire-orchestrator` owns the event store; workers are stateless consumers/producers.
Per-service durability via transactional-outbox → Kafka → idempotent consumers (at-least-once).
Heavier for external adopters — accepted as an explicit choice (ADR-006 is personal/OSS but the author
optimizes for their own topology; simplicity for strangers is secondary).

**Note:** the original modulith rationale (trivial one-container run) still stands as a *possible*
future "all-in-one" packaging if broad adoption ever makes it worthwhile — not pursued now.

**Build sequencing (refinement).** Separate services cannot talk over the SmallRye *in-memory*
connector (it doesn't cross process boundaries), so **Phase 0 runs all modules in one process** (the
in-memory connector as a dev/test harness) to prove the pipeline; **Phase 1+ split into the `spire-*`
deployables over Redpanda/Kafka.** The **target topology stays microservices** — only the build order
is modulith-first, which also de-risks single-process timers and idempotency before Kafka is added.
Modules are written behind the ports from day one so the split is a wiring change, not a rewrite.

---

## ADR-007 — Event store: Postgres for v1; KurrentDB as an optional adapter, not the default

**Decision:** v1 uses an append-only **Postgres** table behind an `EventStore` port. **KurrentDB**
(the rebranded EventStoreDB, first release under the new name = KurrentDB 25.0, 2025) is kept as a
*possible pluggable adapter*, not a hard dependency.

**Why not KurrentDB as the default, despite the best technical fit:** KurrentDB is event-native
(streams, append-with-expected-version, catch-up subscriptions, `$all`, projections) with an official
Java SDK — genuinely the most purpose-built option. **But since v24.10 it is licensed under Event
Store License v2 (ESLv2), a variant of the Elastic License v2 — explicitly NOT OSI open source, i.e.
"source-available."** ESLv2 restricts offering it as a hosted/managed service and gates enterprise
features behind a paid key. For a **public Apache-2.0 project** that others will self-host (and we
might one day host), hard-depending on a source-available, competitor-restricted datastore is a
strategic liability and off-putting to contributors. Self-hosting our own reviews would be fine; a
core dependency is not.

> **Premise updated by ADR-021** (the decision is not): Code Spire is no longer "a public Apache-2.0
> project" — its services are FSL-1.1-ALv2. ADR-021 explains why that does not license a
> source-available *hard dependency* here. Postgres over any source-available engine stands.

**Consequence:** The `EventStore` port hides the choice. v1 = Postgres append-only (permissive,
zero new moving parts, trivially embeddable). If we outgrow it, evaluate license-clean options
(Postgres + thin event-store layer, Marten-style patterns) before any source-available engine. Anyone
who wants KurrentDB's subscriptions/projections can write the adapter.

Sources: https://github.com/kurrent-io/KurrentDB ·
https://www.kurrent.io/releases/kurrentdb/25-0/ ·
https://www.kurrent.io/blog/introducing-event-store-license-v2-eslv2 ·
https://discuss.kurrent.io/t/important-information-eventstoredb-is-transitioning-to-event-store-license-v2-eslv2-with-the-upcoming-24-10-lts-release/5423

---

## ADR-006 — Personal open-source project

> **Partly superseded by ADR-021.** The licence choice is no longer Apache-2.0 repo-wide: the
> libraries and plugin SPI are Apache-2.0, the runnable services are FSL-1.1-ALv2, and the project
> is source-available rather than open source. Everything else below still holds — personal, public,
> built in private time, domain-neutral, no employer entanglement.

**Decision:** Code Spire is a personal, public open-source project (intended Apache-2.0), built
in private time — not internal tooling.

**Why:** The fillable market gap (plugin-first + self-hosted whole-repo RAG + residency-friendly)
is broadly useful, not employer-specific. Open-sourcing brings more options to a market where the
only mature OSS option (PR-Agent) is Python/single-shot and the good tools are closed SaaS. Keeping
it public also sidesteps any internal-IP entanglement.

**Consequence:** All docs are domain-neutral (no employer references). License decided before first
commit. Maintenance is a real commitment (issues, CVEs, releases) — accepted as the cost of the bet.

---

## ADR-005 — Event Modeling as the design method

**Decision:** Model the domain with Event Modeling; formalize with the Fraktalio
`Decider / View / Saga` triad.

**Why:** The review pipeline is a sequence of state changes reacting to facts — a natural fit.
Event Modeling gives a shared blueprint (slices), and the fmodel formalism maps cleanly to pure,
testable, event-sourced components. See [EVENT-MODEL.md](EVENT-MODEL.md).

---

## ADR-004 — Fully event-driven core, no synchronous processing

**Decision:** Components communicate only via asynchronous events/commands (choreography). The only
synchronous edges are at the system boundary (inbound webhook → 202; outbound SCM/LLM API calls),
isolated inside adapter plugins.

**Why:** (1) It is the structural enabler of the plugin-first goal — a plugin is a component that
subscribes to and emits events, so capabilities attach with zero core change. (2) Replayable,
auditable review history. (3) Natural back-pressure and horizontal scale for PR bursts.

**Consequence:** Requires an event store + messaging backbone (SmallRye Reactive Messaging over the
Kafka protocol — Redpanda/Kafka from v1; in-memory connector for dev/test). Idempotent deciders keyed
by event id. Slightly more upfront machinery than a request/response service — accepted.

---

## ADR-003 — Stack: Quarkus + WebSockets + LangChain4j

**Decision:** Quarkus (Java) reactive core; SmallRye Reactive Messaging as the event bus; Quarkus
WebSockets Next for live read-model/token push; LangChain4j for LLM provider adapters.

**Why:** Matches the author's Java competency (explicit non-preference for Python — the deciding
factor against forking PR-Agent). Quarkus gives reactive messaging, CDI-based plugin discovery,
fast startup, and GKE/container friendliness. WebSockets carries the read side / live dashboard.

---

## ADR-002 — Build (hybrid greenfield), do not fork PR-Agent

**Decision:** Greenfield in Quarkus, but **learn from PR-Agent's hard-won algorithms + prompts**
rather than rediscover the same problems from scratch.

> **What actually shipped** (recorded 2026-07-26, during the ADR-021 licensing pass). The original
> wording of this decision was "port PR-Agent's algorithms + prompts", and ARCHITECTURE.md §7 planned
> to convert ~1,500 lines of its Jinja templates. Neither happened. PR-Agent was read as prior art
> (RESEARCH.md §3) and **no upstream code was used** — verified by comparing the shipped code against
> v0.38.0's source, recorded as RESEARCH.md §4: `spire-diff` and `spire-llm` are independent
> Java implementations against Code Spire's own model, and the prompts in `PromptCatalog` are written
> here — a different structure (untrusted-data fencing, our JSON findings contract, reconcile
> verdicts) and a tenth the size. Credit is recorded in `NOTICE`. The wording is corrected because
> the plan's language described the codebase inaccurately, not because the plan was wrong to make.

**Why:** A 5-part code review of `qodo-ai/pr-agent` (22k LOC Python) found: SCM abstraction 3/5
(a 50-method God-object ABC; **thread-reply and PR-author are unimplemented on both Bitbucket
providers** — exactly the two features needed); plugin extensibility **2/5** (hardcoded dispatch
dict, single-shot engine with no tool-use loop, no hook point for RAG); LLM layer 3.5/5 (embedded
LiteLLM; the diff/token/prompt logic is the real IP); RAG/memory **0/5** (diff-only reviews; its one
vector feature indexes *issues, not code*, GitHub-only — the differentiator is greenfield either
way); quality 3/5 (stateless, but 729× global-config coupling).

Cost: extend Python ≈ 5–9 pw · full greenfield ≈ 12–20 pw · **hybrid greenfield ≈ 8–12 pw**.
Extending is ~2× cheaper but saves only ~2k LOC of diff algorithms (which are *portable*) while
leaving the plugin system, RAG/memory, and Bitbucket thread-reply/author to build anyway — on top
of a Python codebase that structurally fights the agentic vision and won't be staffed.

**Port faithfully:** `git_patch_processing.py`, `pr_processing.py`, `token_handler.py`/`clip_tokens`,
YAML-repair, and the ~1,500 lines of prompt templates (→ Qute). **Build clean:** the event-driven
core, the plugin SPI, segregated SCM ports (thread-reply + author first-class), the context-provider
pipeline, injected config.

---

## ADR-001 — Self-hosted, provider-agnostic, one-bot reviewer

**Decision:** A single bot service reviews every PR in a workspace via a workspace/project-level
webhook + one service identity — no per-seat licensing. Source-control platform, LLM provider,
context sources, and storage are all pluggable and chosen at configuration time (no hard defaults;
fail-fast if unset). Code and inference can stay entirely self-hosted.

**Why:** Rules out per-seat SaaS (Rovo Dev is per-user; Qodo Merge/CodeRabbit are per-contributor
and/or SaaS-egress). Greptile — the closest inspiration — does not support Bitbucket at all and is
closed. The one-bot + webhook model is what makes "all PRs, author-agnostic, no seats" true.
Author identity is optional data captured on every event (per-user analytics later), never a gate.

**On LLM routing:** do **not** route inference through GitHub Copilot's backend — it has no official
API; only reverse-engineered OAuth proxies exist (unsupported, ToS-risky). Use direct provider APIs
(Vertex/Anthropic/Azure) or in-cluster models (Ollama) via LangChain4j, selected at config.
