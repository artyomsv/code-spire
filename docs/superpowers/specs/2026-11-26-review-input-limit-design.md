# Review input limit: send the whole diff — design

Date: 2026-11-26. Status: approved by the operator 2026-11-26, revised after review the same day.
Part of #391.

## Intent

An API review sends at most 24,000 estimated tokens of diff to the model (`PromptCatalog.palette`,
`diff` on REVIEW; 12,000 on RECONCILE and FOLLOWUP). Anything past that is clipped and the review is
posted as a "Partial review". The models in the catalog take about 1,000,000 tokens, so a PR of a
few dozen files is already reviewed only in part, while the model could have read all of it.

The operator's words: "we should send the whole diff because otherwise we are loosing the point of
having code review if only fraction of it is being reviewed."

## Goals

1. A REVIEW and a RECONCILE send the whole diff whenever the model's context window can hold it.
2. The diff budget follows the review model: its catalog `contextWindow`, minus room for the answer,
   the rest of the prompt and a safety margin.
3. One app setting, **Review diff limit** (tokens), lets the operator cap the diff lower. Empty means
   "as much as the model's window allows". It caps the diff only, never the other prompt parts.
4. When a diff is still clipped, the posted review says so plainly and names the files the model did
   not see.

## Non-goals

- **Money limits per review.** The operator decided the existing fleet caps are enough.
- **FOLLOWUP replies.** Their diff is one file, already narrowed (`FollowUpWorker`); they keep 12,000.
- **Harness reviews.** A harness agent reads the repository itself; it has no diff budget.
- **The forge's own diff limits** (`Diff.truncated()` from the SCM adapter). Unchanged.
- **The other prompt variables** (`context` 4,000, `code_context` 6,000, `prior_findings` 4,000)
  keep their fixed budgets.
- **Splitting a large diff into several calls**, a real tokenizer, a per-repository limit, a retry
  with a smaller diff after a provider refusal.
- **Long-context price tiers.** See "Known limit: price tiers" below.

## Anti-goals (a failure even if it works)

- A review sends less diff than today. Today's budgets (24,000 REVIEW, 12,000 RECONCILE) are the
  floor for every path, the setting included, except where the model's own window is smaller.
- A review of a model with an unknown window sends more than today. Unknown means today's budgets.
- A clipped review that looks complete. Every diff clip on REVIEW and RECONCILE reaches the posted
  text.
- A rolling upgrade (old worker with new orchestrator, or the other way) that fails a review.
- A paid call re-sent again and again because a large prompt timed out.

## How it works

### What the orchestrator sends

`WorkerLlmCredentials.pack` already resolves the review model and packs `LlmCredential`. It adds two
nullable `Integer` components:

```
maxInputTokens = window − reserve        (null when the catalog has no window for the model)
  window  = ResolvedModel.catalogEntry().contextWindow()   (Long; long arithmetic, clamp to int)
  reserve = provider maxTokens if set, else the default output cap (16,384)
diffTokenCap   = app_setting 'review.diff-limit-tokens'     (null when the setting is empty)
```

The reserve is the output cap the call really sends; the catalog's `maxOutputTokens` is not used.
The 16,384 default moves from package-private `LangChain4jLlmProvider.DEFAULT_MAX_OUTPUT_TOKENS` to
one public constant in `spire-contract` that both modules read. A manual model has no window, so
`maxInputTokens` is null. Every pack site (REVIEW, RECONCILE, FOLLOWUP) gets the same values.

### The diff budget (worker, one pure function)

```
default  = today's budget for the kind (REVIEW 24,000; RECONCILE 12,000)
others = estimate(everything except the diff)
if maxInputTokens == null:   budget = default
else:                        room   = maxInputTokens × 0.75 − others     -- with the 25% margin
                             full   = maxInputTokens − others            -- no margin
                             budget = max(room, min(default, full))
if diffTokenCap != null:     budget = min(budget, max(diffTokenCap, default))
```

In words: with a known window, the diff may use what is left of 75% of `window − reserve` after the
other parts. It never gets less than today's default, unless the window itself cannot hold that
(then it gets what the window can hold, where today's call would have overflowed anyway). The
setting then lowers it, but never under today's default for the kind. The 25% margin
covers the estimator: `TokenBudget.estimateTokens` is `chars / 3.2` with no safety factor, and
dense text (lock files, minified code, non-Latin text) has fewer characters per token. The builder
writes the exact arithmetic as one unit-tested function; the rules above are the contract.

"Everything except the diff" is the system message (persona, locked suffix, rules) plus the user
message rendered with an empty diff. `PromptRenderer.render` gains a budget override for `diff`
(today it reads budgets only from the static palette); a body that uses `{{diff}}` more than once
counts the diff once per use. If `room` is zero or less with a known window, no call is made and
the review fails with a reason naming the model's window.

### Clipping a REVIEW diff at files

REVIEW has the parsed `List<FilePatch>` (`ReviewPromptBuilder`):
- Files are kept in their existing order. A file that does not fit in what is left is **skipped**,
  and later smaller files still go in.
- Only when no whole file fits at all is the first file clipped inside, at a line boundary, as
  `TokenBudget.clip` does today.
- The worker records the paths of files skipped or clipped.

RECONCILE gets a raw compare diff string. It keeps line clipping (`TokenBudget.clip`), but the
worker parses that string with `UnifiedDiffParser` to learn which files were cut, so it can name
them. A clipped RECONCILE must never resolve a finding on a file the model did not fully see:
`ReconcileSteps` forces the verdict for such findings to UNCHANGED.

### How the clip reaches the posted text

Today `ReviewResult.truncated` is one boolean, and `PromptRenderer` sets it when ANY fenced value
was clipped (`context` and `prior_findings` too). The change:

- `PromptRenderer` reports which variables were clipped, not one flag.
- `ReviewResult` gains `List<String> omittedFiles` (paths the model did not fully see; empty when the
  diff was not clipped), with a wither next to `withTruncated`. `truncated` keeps its meaning for
  any clip.
- The "Partial review" note (`ReviewWorker`, ~line 735) lists up to 20 omitted paths, then "and N
  more", and says the diff exceeded the review input limit (model window or the Review diff limit
  setting; the note does not need to say which).
- `ResultSaga` (~line 586) writes its sentence from the same data.
- A clipped RECONCILE sets these too (today `ReconcilePrompt` drops the flag).
- `ReviewResult` is a nested wire type that `ContractSchemaSnapshotTest` does not recurse into, and
  it is stored in the idempotency store: add decode tests in both directions (old JSON into the new
  record; new JSON through the old shape is ignored by the Quarkus default mapper) and check every
  rebuild site (CLAUDE.md, "Adding a component to a wire record").

### Timeouts and retries

The default model call timeout is 180 s, non-streaming, and a timeout is retryable
(`WorkerFailures`), so a large prompt that times out is re-sent whole and may be billed each time.

- Default `spire.llm.timeout-seconds` becomes 600. Default `SPIRE_KAFKA_ACK_MAX_AGE_MS` becomes
  1,800,000 so `LlmTimeoutBudget` still passes (a re-review makes two calls: 2 × 600 + posting
  backoff). Both are changed in every place the defaults live (application.yml, Helm values,
  compose, `.env.example`), and the Upgrading note tells operators who set them by hand.
- A timeout of a call whose diff budget was above today's default is **not retried**: the review
  fails with a reason that says the prompt was large and names the Review diff limit setting.
  Timeouts of small calls stay retryable as today.
- A provider refusal ("prompt too long", a rate limit) is the existing failed review with the
  provider's message.

### The setting

- Key `review.diff-limit-tokens` in `app_setting`. Empty (no row) = as much as the window allows.
- Accepted values: a whole number from 24,000 to 2,000,000. Anything else is a 400.
- A stored row outside that range (edited by hand) packs as 24,000, a 24,000-token diff cap (with a known
  window a RECONCILE may get up to 24,000, more than its old 12,000), with a WARN and a
  `DIFF_LIMIT_INVALID` attention row; the form shows it as invalid and a blank Save deletes it. A failed read
  of the row retries the review through the saga's bounded retry; it never reads as unset (PR 456, round 2).
- Settings → General, admin only, through the existing app-setting resource pattern (as
  `llm.default-model`), read through React Query like the other General settings.
- Help text: empty sends the whole diff when the model can hold it; a number caps the diff of every
  API review; a larger input costs more; fleet caps still apply; long-context price tiers are not
  modelled (below).

The prompt editor does not change (`PromptsSettings.tsx` does not show variable budgets today).

## Known limit: price tiers

`ai-catalog/v1/catalog.yaml` does not model OpenAI's long-context tier (input above 272K) or Claude
Haiku 5.5's (above 100K); such calls are priced at short-context rates, so the ledger and the fleet
caps read low for them. This change makes such calls possible. The operator chose "whole diff"; the
spec does not clamp to the tier. It records the gap in `docs/UNVERIFIED.md` and in the setting's
help text, and the operator may set the Review diff limit (for example 60,000, since it caps only the diff and the other prompt parts add about 14,000 tokens) to stay below the
tiers until they are modelled.

## Tests (what proves it)

- Budget function: unknown window = today's defaults; known window lets a diff far above 24,000
  through; a small window gives `room`; the setting lowers it but not under 24,000; zero room fails.
- Reserve: provider `maxTokens` changes it; unset uses 16,384; a 2,000,000 window does not overflow.
- File clipping: an oversized first file is skipped and later files go in; nothing fits clips the
  first file inside; omitted paths are recorded.
- `PromptRenderer`: a clipped `context` alone does not produce omitted files; `{{diff}}` twice counts
  twice.
- RECONCILE: clipped diff sets the note; a finding on an omitted file stays UNCHANGED.
- Wire: both decode directions for `LlmCredential` and `ReviewResult`.
- Timeout: a large call's timeout is not retried; a small call's still is.
- Setting resource: bounds, empty clears, admin only. UI: the field saves and clears.
- Each guard mutation-checked (CLAUDE.md).

## Live proof (dev stack, `spire-test`)

1. A PR whose diff is well above 24,000 estimated tokens, reviewed on a catalog model with the
   setting empty: no "Partial review"; record the call time and the charge.
2. The same PR with the setting at 24,000: "Partial review" naming the files left out.
3. A re-review after a new commit, setting at 24,000 and a large incremental diff: the note appears;
   no finding on an omitted file is resolved.

## Docs

- `docs/HISTORY.md`: the milestone entry and an Upgrading note (any order is safe; no migration;
  the new timeout and ack defaults).
- `docs/UNVERIFIED.md`: the price-tier gap, and whatever the live proof does not reach (a manual
  model, a rate-limited key, a real timeout).
- `docs/CONTRACT.md`: the new `LlmCredential` and `ReviewResult` components.

## Builder's choices

- Names of new classes; where the budget function lives (pure, unit-tested).
- How `PromptRenderer` reports clipped variables.
- Settings → General layout and help wording within the meaning above.

## Calls made by the designer (not decided by the operator)

- Only `diff` becomes dynamic, and only on REVIEW and RECONCILE.
- Unknown window = today's budgets; today's budgets are the floor.
- 25% margin; setting range 24,000 to 2,000,000; the setting caps the diff only.
- Reserve = provider `maxTokens`, then 16,384.
- Skip oversized files rather than stop at them; name omitted files.
- No clamp to long-context price tiers; documented instead.
- New defaults: LLM timeout 600 s, Kafka ack age 1,800,000 ms; large-call timeouts are not retried.
- A provider "too long" refusal is a failed review, with no smaller retry.
