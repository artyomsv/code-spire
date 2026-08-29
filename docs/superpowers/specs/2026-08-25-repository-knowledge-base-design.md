# Repository knowledge base — design

**Status:** proposed
**Date:** 2026-08-25
**Roadmap item:** P3 (recorded there as "Whole-repo RAG")
**Supersedes:** `ARCHITECTURE.md` §5 step 3 (`PushReceived` -> `RepositoryIndexDecider`)
**Proposed ADR:** ADR-026 — repository knowledge base is derived, structural, and confirmed at citation

---

## 1. What this builds, in one paragraph

Reviews today see the diff, the PR text, the repository's `.codespire` rules, and whatever a
configured context provider resolves from the PR's references. They do not see the rest of the
repository. This adds a repository knowledge base that supplies the code a change *depends on* —
starting with the definitions of the symbols a diff actually touches — as ordinary
`ContextItem{kind=CODE_SNIPPET}` contributions through the existing `ContextProvider` SPI. It is
built in two rungs: **rung 1 stores nothing**, and **rung 2 stores structure but never content**.
No embeddings, no vector store, no crawl, and no background indexer.

## 2. Why not the design the roadmap assumed

The roadmap specifies embeddings, a pluggable vector store and a push-triggered indexer. Four
findings against the shipped codebase argue otherwise, and this section records them because the
name "RAG" will otherwise keep re-proposing that design.

**2.1 The exit criterion does not require retrieval-by-similarity.** ROADMAP's stated exit for P3 is
"reviews reference code elsewhere in the repo, not just the diff". Definition lookup meets that with
no vector store. The technique was named before the need was.

**2.2 Only similarity search needs the apparatus, and it buys the weakest findings.** Of the four
things whole-repo context could deliver — definitions of what the diff touches, similar code
elsewhere, call-site impact, and inferred conventions — only *similar code elsewhere* is a
similarity problem. Definitions and callers are deterministic lookups; conventions are a one-shot
summarization. Similarity is also the only one that simultaneously requires stored source, a cold
crawl, staleness management, and embedding spend with no `call_ref` scheme under ADR-023.

**2.3 The repository size range rules out any crawl-based design.** Target repositories span roughly
10 files to 10,000. A crawl cannot complete inside the aggregator's 20-second budget at the large
end and is pointless at the small end, so no single crawl-based design serves both. What survives the
whole range is the option whose cost scales with the **diff** rather than the repository: a
three-file pull request is a three-file pull request in either. In a very small repository, import
closure from the diff reaches most of the codebase anyway — total coverage falls out of the same
mechanism, with no size threshold, no second code path, and no operator-visible cliff.

**2.4 A push-fed index is strictly worse than a review-time one, and breaks an invariant to be so.**
`CONTRACT.md` §9 states every topic is keyed by `reviewId` and calls the keying discipline "the
important invariant". A push carries no `reviewId`, so a push-fed index would introduce the first
non-`reviewId` message class in the system. It would also be *less* correct: the index's only reader
is a review, and a review-time refresh keys the index to the exact commit under review, which a
push-fed index cannot guarantee because it can lag the pull request head. `PushReceived` stays
declared and unemitted; `ARCHITECTURE.md` §5 step 3 is removed rather than deferred.

**Consequence for the roadmap's other open contradiction.** ROADMAP names Qdrant/LanceDB as the
vector store while `DATA-MODEL.md` §1 and §6 name pgvector. This design needs neither, so the
contradiction is not resolved here — it is marked as unresolved and deferred to whenever similarity
search is actually scheduled, at which point pgvector wins on the one-Postgres grounds ADR-011
already states.

## 3. Scope

**In scope (rung 1, buildable now):** diff-derived code references; a `spire-context-code` provider
resolving them to definitions; a dedicated prompt slot; `LanguageSupport` for **Java and
TypeScript**.

**Specified but gated (rung 2):** a grown symbol index enabling call-site impact. Built only if
rung 1 clears the evidence gate in §9.

**Explicitly not built:** similarity search, embeddings, a vector store, a repository crawl, a
`PushReceived` consumer, `spire-indexer` as a deployable. Inferred repo conventions (an
operator-invoked generator producing a draft `.codespire` for human review) is a good idea and a
separate roadmap item; it shares no machinery with this one.

## 4. Architecture

### 4.1 Data flow

```
DiffWorker.fetchDiff                         (parsed Diff already in hand)
  |- references.referencesIn(title, branch, description)   [exists: issue keys]
  '- codeRefs.inDiff(diff)                                 [NEW: changed paths + identifiers]
        -> DiffFetched.codeReferences
        -> GatherContext.codeReferences
        -> ContextRequest.codeReferences
ContextWorker fan-out (20s budget, unchanged)
  '- CodeContextProvider.contribute()
        |- rung 1: resolve symbol -> file -> snippet
        '- rung 2: SymbolIndex.callersOf(symbol), confirmed before citing
```

### 4.2 A separate wire field, not the existing `references` set

`codeReferences` is a new field carrying **changed paths** and **identifiers appearing in changed
lines**. It does not share the neutral `references` set, for two reasons that run in opposite
directions:

- `ContextWorker`'s level-2 collection mines level-1 **item bodies** for new references. A
  `CODE_SNIPPET` body is an item body, so a `PROJ-123` in a code comment would be fetched as a Jira
  ticket. Code snippets are therefore also **excluded from the level-2 corpus** entirely.
- The `references` set is documented as recall-favouring on the grounds that "a false candidate costs
  nothing but an unmatched string". That holds at ticket-key volume. A diff yields tens to hundreds of
  identifiers, scanned by every registered provider.

Both fields carry **metadata only** — paths and identifiers, never hunk text. "ADR-011 is untouched"
overstates it: identifiers harvested from a diff's changed lines are diff-derived tokens, and they now
ride the Kafka bus on `DiffFetched.codeReferences` — a change ADR-011's no-diff-persistence intent did
not anticipate, and `WorkerPipelineTest.fetchDiffEmitsMetadataOnly` had to be narrowed to accommodate
it. This is a deliberate, reasoned exception rather than either an untouched invariant or a silent
regression: `DiffFetched` already carries `repoRules` — the entire text of the repository's
`.codespire` file, whenever one exists — so the bus already carries whole-file content by design, and
a set of identifiers cannot reconstruct a diff the way that full-file text could. See ADR-026's
Consequences for the same point recorded against the decision itself.

### 4.3 Module layout and licensing

| Piece | Module | Licence |
|---|---|---|
| `CodeContextProvider`, `LanguageSupport` implementations, source readers | **`spire-context-code`** (new) | Apache-2.0 |
| `SymbolIndex` port | `spire-contract` | Apache-2.0 |
| `PostgresSymbolIndex`, `worker.code_symbol` | `spire-review-worker` | FSL-1.1-ALv2 |
| Composition (`WorkerContextClients`) | `spire-review-worker` | FSL-1.1-ALv2 |

ADR-021 forbids an Apache-2.0 module depending on a service module, so the provider cannot reach the
worker's schema directly. It follows the `BlobStore` precedent: port in `spire-contract`, Postgres
implementation in the worker, injected in. **The port is defined in rung 1 even though rung 1 does
not read it** — otherwise rung 2 becomes a refactor of rung 1 rather than an addition to it.

`spire-arch`'s provider-neutrality allowlist needs no new entries: `WorkerContextClients` is already
an allowlisted composition root, and the per-platform source readers live in an adapter module.

### 4.4 Credentials

The provider holds its **own** registry-brokered token, exactly as `spire-context-github` does. The
context aggregator still holds no SCM credential — that invariant is untouched. Two side benefits:
the code provider works when the review's SCM is a different platform, and a repository can be read
with a token narrower than the review bot's.

## 5. Extraction

### 5.1 Imports are the resolution mechanism, not just an extraction one

For import-based languages the repository already contains a hand-written, compiler-verified
dependency graph. Reading it costs one file fetch and a parse:

| Language | Import to location |
|---|---|
| Java | `com.acme.Foo` -> `**/com/acme/Foo.java`, mechanical given source roots |
| TypeScript | `./foo`, `../bar/baz` -> relative to the importing file, plus extension and `index.ts` resolution |
| Python | `from a.b import c` -> `a/b.py` or `a/b/__init__.py` |
| Go | import path -> module-relative |
| C#, Ruby, PHP | namespace does not determine path — **requires rung 2's index** |

This is what makes rung 1 indexless, and it draws rung 2's boundary at a real seam rather than a
convenient one: rung 2 is exactly what brings in the languages where imports stop determining
location.

### 5.2 Two phases, split at the wire boundary

A diff contains **hunks, not files**, and imports live at the top of a file. A hunk in the middle of
a file does not include the import block, so extraction cannot be completed at diff-fetch.

**Phase 1 — `DiffWorker`, where the parsed `Diff` already sits:** changed paths, plus identifiers
appearing in **changed lines only** (added and removed, not context lines), keyword-filtered per
language. The identifier set is the precision lever: it is what later distinguishes "this file
imports forty things" from "this change touches three of them".

**Phase 2 — the provider:** for each changed path, fetch the file **at the review commit**, parse its
import block, intersect with the phase-1 identifiers, resolve surviving imports to candidate paths,
fetch those, emit snippets.

Phase 2 re-fetches content the diff partly held. That is deliberate: the alternative is putting
import lines on the wire, which is file content under another name.

### 5.3 The `LanguageSupport` SPI

Mirrors `ContextReferenceSource`, so adding a language is a bean and not a core edit:

```java
public interface LanguageSupport {
    Set<String> languages();                          // "java", "typescript" — Languages.of() tags
    Set<String> identifiersIn(Hunk hunk);             // phase 1
    List<ImportRef> importsIn(String fileContent);    // phase 2
    List<String> candidatePaths(ImportRef ref, String importingPath);
}
```

`FilePatch.language` is already populated by `Languages.of(path)`, so dispatch is free. A file whose
language has no `LanguageSupport` contributes nothing and the review proceeds exactly as today — the
same degradation posture as an absent `.codespire`.

Rung 1 ships **Java** and **TypeScript**.

### 5.4 Source fetching

The provider uses its own client, not `DiffSource`. `DiffSource.fetchTextFileOnBranch` is
branch-keyed on purpose (the `.codespire` target-branch trust rule) and this design needs the review
**commit**. Three thin readers — GitHub, GitLab, Bitbucket raw-content APIs — live in
`spire-context-code`. Note `spire-http`'s `PinnedJsonClient` is JSON-shaped; raw file content needs a
sibling in the same module, which is the reuse `spire-http` was extracted for.

## 6. Retrieval, ranking, and the prompt budget

### 6.1 Prompt slots are budgeted independently

`PromptRenderer` clips each variable against its own `PromptVariable.maxTokens`. Current REVIEW
palette: `diff` 24,000, `context` 4,000, `prior_findings` 4,000. Code snippets therefore **cannot**
crowd out the diff.

### 6.2 Code context gets its own slot

`{{context}}` is 4,000 tokens shared by tickets, pages, issues and rules; `renderContext`
concatenates in list order and `TokenBudget.clip` cuts the tail. Which context survives is decided by
arbitrary ordering. Adding snippets there produces a feature that on a repository with a chatty Jira
ticket is silently truncated away — tests green, review normal, the only trace a `truncated` boolean
nobody reads. That is the same silent-success failure shape as an LLM circuit breaker recording a
failed future as a success.

A new palette variable **`{{code_context}}`, budget 6,000 tokens**, makes eviction impossible rather
than unlikely, and mirrors the existing design language (`prior_findings` has its own slot for the
same reason).

**Consequence, deliberately surfaced:** adding a palette variable changes the built-in default
templates, and an operator whose template is customized will not have the new slot — their reviews
would get no code context, silently. The mitigation already exists: `PromptDriftBanner` (V33) is
built to surface "the default moved under your customization". A test asserts the banner fires for a
template lacking `{{code_context}}`.

`maxTokens` lives in the palette, not the template, so 6,000 is a code constant and not
operator-tunable per repository. Making it tunable is a separate change and is out of scope here.

### 6.3 Snippet shape

Declaration, plus doc comment, plus body clipped to approximately 40 lines using the existing
truncation marker. **The signature always survives clipping** — the high-value information ("returns
millicents", "throws on 404", nullability) lives in the signature and doc, not the body.

### 6.4 Ranking

Candidates will exceed budget. Ranking is explainable rather than clever:

1. ~~Symbols referenced in added or modified lines before removed-only ones.~~ **Not implemented in
   rung 1, deliberately** (M4, rung-1 final review — this row previously claimed delivery it did not
   have). `CodeReferences` carries one identifier set with no added/removed split, so there is nothing
   for this tie-break to read; adding it means a third set on `CodeReferences`, which is a wire change
   out of scope for rung 1. `CodeContextProvider.rankAndCap`'s own javadoc states the deferral
   honestly — this spec previously did not.
2. By the number of **distinct changed files** referencing the symbol — a symbol used across
   several changed files is more central to the change.
3. Ties by first appearance.

A hard cap on snippet **count** as well as tokens, so one pathological file cannot consume the slot.

### 6.5 Fetch economy

One fetch per `(path, commit)` per review, cached in-provider: several symbols resolving into one
file cost one call. Bounded by the aggregator's existing 20-second budget. Unresolved symbols simply
do not appear.

## 7. Rung 2 — the symbol index

### 7.1 The index is a hint, never a source of truth

Nothing is cited from the index. To answer "who calls this", the provider takes candidate paths from
the table, **re-fetches the top candidates at the review commit, and confirms the reference still
exists**. Only confirmed references become snippets.

This removes the staleness problem structurally rather than managing it. There is no invalidation
pass, no indexed-commit versus review-commit reconciliation, and no path by which a stale row
produces a finding about code that no longer exists, because every citation was read moments before
it was cited. An index that only narrows the search may be arbitrarily wrong and remain safe; an
index that answers the question must be kept correct forever. Confirmation fetches are the same
order of magnitude as rung 1's, under the same budget.

### 7.2 Schema — structure only, never content

```
worker.code_symbol
  repo             text          -- workspace/slug
  symbol           text          -- identifier
  path             text          -- file containing the reference
  role             text          -- DEFINES | REFERENCES
  last_seen_commit text
  last_seen_at     timestamptz
  PRIMARY KEY (repo, symbol, path, role)
```

`callersOf(symbol)` is `WHERE symbol = ? AND role = 'REFERENCES'`. Reverse edges fall out of
recording each file's outbound references; no separate structure is needed.

`last_seen_commit` and `last_seen_at` are **diagnostic and pruning metadata only**. Neither
invalidates a row, and no read compares them against the review commit — per §7.1 there is no
invalidation pass at all, because confirmation at citation time makes one unnecessary. An
implementer who finds themselves writing "if `last_seen_commit` != review commit then ..." has
reintroduced the design this section exists to avoid.

### 7.3 Encryption: unencrypted, and consistent with existing practice

Symbol names and paths are stored in clear because an encrypted column cannot be queried server-side
and this table exists to be queried. This matches what the codebase already does: `review_finding`
and `review_thread` store `path`/`line` unencrypted because the location index is queried by
location, while findings' *messages* are encrypted. Coordinates are queryable; content is encrypted.

The line this design holds is **structure is stored, content never is** — no hunk text, no file
bodies, no snippets. Nothing here is source, so ADR-011 needs no amendment and no carve-out.

Residual to state in `SECURITY.md` rather than leave implicit: symbol names leak domain vocabulary.
The exposure is the operator's own Postgres, inside the same trust boundary that already holds their
findings and file paths.

### 7.4 Growth and bounds

Every file a review fetches — changed files plus rung 1's definition hits — has its symbols recorded.
The index grows toward the part of the codebase that is actively changing, which is the set this
feature needs. It never crawls, so a 10,000-file monorepo never has most of itself indexed, and that
is correct rather than a limitation.

Bounded by pruning rows not confirmed within a retention window. Because the index only generates
candidates, pruning costs **recall, not correctness** — the worst outcome is a caller not mentioned.

### 7.5 Partial recall must be stated, not implied

A finding claiming "this breaks all 3 callers" when twelve exist is a fabrication: the
no-synthetic-data rule applied to completeness. Snippets carry "known callers" framing, and the
prompt is written so the model cannot claim exhaustiveness from a set it was handed.

### 7.6 What rung 2 deliberately leaves open

If similarity search is ever wanted, it adds a column to this table rather than a new subsystem.
That is the payoff for designing the table in rung 1 even though rung 1 does not read it.

## 8. Failure modes, observability, testing

### 8.1 Degradation

| Failure | Handling |
|---|---|
| Unsupported language | No code references. Review proceeds unchanged. |
| File fetch 404 | Skip that symbol. **Not** counted as a circuit failure — a moved file is normal. |
| File fetch 401/403 | **Not wired to an attention row.** `CodeContextApiException.isUnauthorized()` exists and is correct, but nothing in production calls it: `Fetcher.read` catches `RuntimeException` uniformly and reports the path as absent either way, so a rejected credential is indistinguishable from a moved file at that point. A rejected credential surfaces only as `CODE` in `ContextAssembled.missingSources` on individual reviews, plus the registry's own Check button — never as an automatic attention row the way an SCM credential rejection does. Wiring it needs the contribution to carry the outcome out to `ContextWorker`, which did not fit this branch; tracked in `techdebt/spire-context-code/` (I4, rung-1 final review). |
| Rate limited (429) | **Not distinguished from any other failure.** `CodeContextApiException.retryAfterSeconds()` is structurally always `null` — `PinnedJsonClient`'s failure callback carries no response headers to parse a `Retry-After` value from — so there is no retry-after-aware backoff on this path. `CircuitBreakingSourceFileReader.isUnhealthy` only counts `status >= 500`, so a 429 does not open the per-host circuit either; it simply fails that one candidate path and moves on. The `Retry-After` ladder belongs to the SCM adapters' own clients (`RetryingDiffSource`), not this one — see `techdebt/spire-context-code/`. |
| 20s budget exhausted | Partial contribution; whatever resolved, ships. |
| Symbol resolves to several paths | Skip rather than guess. A wrong definition is worse than none. |
| Index query fails (rung 2) | Degrade to rung 1. Callers absent. |

The code provider **shares** `ProviderCircuits`' per-host key rather than taking its own: the host is
the resource being protected, and a genuinely unwell host should pause reviews too. 404 must not
count toward it, or a repository with moved files opens the circuit against its own reviews.

### 8.2 Distinguishing "nothing to do" from "systematically broken"

`ContribStatus.EMPTY` is legitimate — a YAML-only diff has no symbols — so an attention row on EMPTY
would be wallpaper, the reasoning that already kept `CREDENTIAL_UNVERIFIED` off the panel. But two
states surface identically as EMPTY and are not equally fine:

- extracted 0 -> nothing to do, correct;
- extracted 40, contributed 0 -> resolution is systematically broken, a defect.

The provider records `extracted / resolved / contributed / dropped-for-budget` and logs them under
the reviewId MDC. This is the `ConversationSaga` precedent applied deliberately: its decision factors
were added after several bugs proved invisible because the skips reached only the dashboard. Counts
carry no source text and are safe to log.

### 8.3 Tests that must be mutation-verified

Each must fail when the production line it covers is reverted.

1. Identifiers come from **changed lines only** — mutate to include context lines; the test fails.
2. Code references never enter the `references` set — assert none reaches the ticket/issue providers.
3. **Level-2 contamination:** a `CODE_SNIPPET` body containing `PROJ-123` triggers no Jira fetch.
4. **Slot independence:** an oversized `{{context}}` does not evict code snippets, and vice versa.
5. **Confirm-before-cite:** an index row whose symbol is gone from the file produces no snippet.
6. **Snippets reach the model** — extend `ReviewWorkerTest.assembledContextReachesThePromptSentToTheModel`,
   which already asserts assembled context reaches the captured `Prompt` and was confirmed to
   discriminate when `contextRef` is null.
7. **Template drift:** an operator template lacking `{{code_context}}` raises the drift banner.

Test data uses obviously-synthetic identifiers and `example.invalid` hosts. No fabricated repository
content is committed as a fixture beyond what a unit test needs to drive a parse.

## 9. The evidence gate

Rung 2 is built only if rung 1 clears this bar, and the criterion is fixed **before** the measurement
so the result cannot be rationalized after the fact.

**Method:** a set of real pull requests already reviewed by this deployment, re-run with recorded
controls — the technique already used to validate the FOLLOWUP persona change. Real PRs from real
repositories; a synthesized diff would measure the fixture, not the feature.

**Pass:** code context produces at least one **new finding judged correct by the operator**, and does
not increase false positives.

**Fail:** repository context does not move findings. The honest outcome is that P3 stops there,
having cost a fraction of its estimate to learn.

### 9.1 Result — run 2026-08-29: FAIL (null), P3 closed at rung 1

Five merged pull requests from this repository (#38, #40, #42, #43, #61) plus one from the test
repository, each reviewed twice through the real pipeline with only the code-context provider
toggled. One further pull request (#76) was excluded: its treatment arm exceeded the LLM request
budget. Cost: about $4.50.

| | |
|---|---|
| Found by both arms | 10 |
| Only **with** code context | 7 |
| Only **without** | 8 |
| Noise floor — identical arm run twice | **5 differing findings on one PR where nothing changed** |
| Findings by type | control 3 code / 15 docs · treatment 3 code / 14 docs |

The toggle produced no more variation than rerunning the same configuration, so the 7 does not
survive contact with the noise floor. Under the criterion above, that is a fail.

**Why the null is corpus-limited.** Code context can only ever change a *code* finding, and there
were three of those in both arms. Nearly every finding — and nearly every difference — landed in
`DECISIONS.md`, `SMOKE-TEST.md` or an implementation plan, which no retrieved code snippet could have
produced. The gate did not falsify rung 1; it established that this repository is majority
documentation and cannot serve as the test bed.

**What §9 was missing, and what a re-run needs.** The criterion above fixes a pass/fail rule but says
nothing about whether the corpus can discriminate — so a perfectly executed measurement can be
pointed at nothing. A future run must additionally require:

1. **A corpus criterion** — pull requests that are majority code, with cross-file dependencies.
   Verifiable up front by counting code versus non-code files in the diff.
2. **A mandatory noise-floor run** (the same arm twice). At the observed variance a single pair
   cannot resolve the effect, and without it a difference of 7 reads as a result.
3. **The three per-run controls** the harness already enforces: both arms are first reviews, the arms
   genuinely differed (control 0 snippets, treatment > 0), and each run returned something parseable
   (`degraded = false`). Each of those was added because its absence had already produced a wrong
   answer — the first attempt, on 2026-08-28, measured a token cap and would have reported it as a
   null about the feature.

The harness and its README are committed at `docs/superpowers/gates/`.

## 10. Documentation changes this requires

- `ARCHITECTURE.md` §5: remove step 3 (`PushReceived` -> `RepositoryIndexDecider`), record why a
  review-time refresh is both simpler and more correct, and keep the "zero core change" claim only
  for *contribution* — acquisition is honestly not free.
- `ROADMAP.md`: restate P3 in terms of what is built; mark the Qdrant/LanceDB versus pgvector
  contradiction as unresolved and deferred, not silently dropped.
- `DATA-MODEL.md`: add `worker.code_symbol`; leave the "no source in our storage" decision standing,
  since this design does not breach it.
- `SECURITY.md`: symbol names as stored domain vocabulary; code snippets are untrusted retrieved
  content and get the existing fenced treatment.
- `DECISIONS.md`: ADR-026.
- `techdebt/`: none created by this design; the `spire-context-code` source readers must not become a
  fourth hand-rolled redirect loop, which `RedirectHandlingHasOneHomeTest` already enforces.

## 11. Open questions

1. **Java source roots.** Mapping `com.acme.Foo` to a path assumes a source root (`src/main/java`).
   Multi-module repositories have several. Rung 1 tries a small ordered set of conventional roots and
   skips on ambiguity; whether that is sufficient is the first thing dogfooding will show.
2. **TypeScript path aliases.** `tsconfig.json` `paths` mapping is common and not resolvable from the
   import specifier alone. Rung 1 does not read `tsconfig.json`; aliased imports go unresolved, which
   is a recall gap, not an error.
3. **Same-package references.** Java requires no import for same-package symbols, so rung 1 misses
   them. Path convention covers part of this; rung 2's index covers the rest.
4. **Whether `{{code_context}}`'s 6,000-token budget should be operator-tunable.** Deliberately not in
   scope; revisit if dogfooding shows the constant is wrong for real repositories.
