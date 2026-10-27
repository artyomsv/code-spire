# Specification, plan and steps acceptance record

M4 slice 3, "specification, plan and steps", is accepted on its live proof (§8 of the
[design](../superpowers/specs/2026-10-25-factory-m4-spec-plan-steps-design.md), the
[plan](../superpowers/plans/2026-10-25-factory-m4-spec-plan-steps.md) and ADR-049). On `artyomsv/spire-test`,
four tickets with one line of text each became a specification with acceptance criteria and a plan, were built
step by step on one chain of checkpoints with each step verified, and were delivered as one pull request,
reviewed and landed. The human steps were write, label, approve the plan and approve land; on two tickets the
operator also pressed Revise with a note, and on one Re-admit with Regenerate.

With that, **M4's exit criterion is met**: ticket #75 had no acceptance criteria, became a specification, then
a plan of three steps, then three verified checkpoints on one branch, then one pull request, and the prompt of
step 3 contained the decision step 1 recorded. The criterion said "three branches"; the design (A2) builds the
steps on one chain of held checkpoints on one branch, which is what ran.

The proof ran on 2026-10-26 and 2026-10-27 on the dev stack, built from PR #287, under the profile
`spire:assisted`. The repository's build setup said preparation `generated`, harness Codex on a subscription
seat with the model `gpt-6-astra`, the verify command `test -f README.md`, land method `squash` and the
ticket rule `comment`. Every specification, plan and step run was paid by the seat. The proof found two defects
and one gap; the defects were fixed on PR #287 before it merged and are listed under "What the proof found".
PR #287 merged to master on 2026-10-27 as `6583d915`.

The factory account is `code-spire-beaver`. `spire-test` has a REVIEWER webhook and no FACTORY webhook, and
its "Reviewer may approve" setting is off, so every review in this proof was a COMMENTED pull request review
(ADR-047, as designed).

## The four tickets

All times are UTC on 2026-10-26, except #77 on 2026-10-27.

| | #70: the whole chain | #71: Regenerate, Revise, an empty step | #75: the M4 exit criterion | #77: the fixes, seen live |
|---|---|---|---|---|
| Ticket text | "This repo needs a small docs area with an index, a changelog and a contributing guide, linked from the README." | "Add an FAQ page for this repo." | "Add ADR md to the repo (Architectural decision record)" | "Add a glossary page and a release checklist page to the docs, and link both from the docs index." |
| Specification run | 20:56:02 → 20:56:46, posted 20:56:48 | gen 1: 21:02:42 → 21:03:52; gen 2: 21:17:42 → 21:18:28 | 21:41:41 → 21:42:36, posted 21:42:40 | 09:36:27 → 09:37:49, posted 09:37:53 |
| Plan run | 20:57:02 → 20:57:52, 1 step | gen 1: 1 step; gen 2: 21:18:42 → 21:19:40, 1 step; Revise → 21:25:22 → 21:26:29, 3 steps | 21:43:01 → 21:44:03, 1 step; Revise → 21:44:41 → 21:46:00, 3 steps | 09:38:07 → 09:39:52, **2 steps, no Revise** |
| Plan gate | approved | gen 2: rejected with a note, then approved 21:26:29 | rejected with a note 21:44:04, approved 21:46:01 | open (`plan / waiting_approval / plan_written`) |
| Steps (checkpoint, verify) | 1: `c5f427e0`, PASSED 21:01:07 | 1: **empty**, head stays `b1e1a362`; 2: `1281b986`, PASSED 21:28:18; 3: `9a6c58a6`, PASSED 21:29:40 | 1: `44a6093a`, PASSED 21:47:22; 2: `34433c97`, PASSED 21:48:23; 3: `4b4739c4`, PASSED 21:49:37 | not built |
| Step summaries (`work_step_summary`) | 1 recorded | 3 recorded (91, 506 and 844 characters) | 3 recorded (810, 598 and 769 characters) | none |
| Pull request | 72, 1 commit | 74, 2 commits | 76, 3 commits | none |
| Land | approved 21:02:11; merged 21:03:44 → 21:03:51, squash `b1e1a362` | gen 1: **rejected**, item stopped; gen 2: approved 21:30:41; merged 21:35:26 → 21:35:37, squash `76515aac` | approved 21:50:31; merged 21:59:23 → 21:59:31, squash `25367c21` | none |
| Item end | `complete / completed / landed`, generation 1 | `complete / completed / landed`, generation 2 | `complete / completed / landed`, generation 1 | waits at the plan gate |

**#70** is the whole chain on a generated repository: a one-line ticket, a specification posted to the ticket
with Outcome, Context, Acceptance criteria, Out of scope and Open questions, a one-step plan, one build on the
seat, verify, one pull request, a review and a land. The specification comment rendered as a grey code block,
defect 1 below. The step's summary was written by the real agent and recorded.

**#71** proves Regenerate, Revise and the empty-step rule. Generation 1 ran to the land gate, where the operator
rejected land and the item stopped. Re-admit with Regenerate started generation 2: a new specification and a new
plan, both run again. The operator pressed Revise on the plan with a note asking for three steps, one of which
should change nothing; the revised plan had three steps, the first "Check that README.md exists without changing
files". Step 1 committed nothing and completed as empty: its head stayed the base `b1e1a362`, it was not verified,
and its summary says it recorded no decisions. Steps 2 and 3 built on the chain and verified. PR 74 lists the
three steps and their summaries, and the prompt of step 3, read from the `cs.run-commands` topic, carried
"Step 1 recorded no decisions" and step 2's decisions.

**#75** is the exit criterion. The ticket named no acceptance criteria. The specification gave it some; the first
plan had one step, and after a Revise with a note the plan had three, each changing files. The three steps built
`44a6093a`, `34433c97` and `4b4739c4`, each descending from the previous head, each verified within a minute of
its build (21:47:22, 21:48:23, 21:49:37). The prompt dispatched for step 3 (offset 80 on `cs.run-commands`)
contained, under "Decisions recorded by earlier steps", step 1's recorded decision that the ADR's status remains
Proposed, and step 3 acted on it. PR 76 carried the three commits, was reviewed and landed. The operator's word
after this ticket: all landed.

**#77** was created by the operator after defects 1 and 2 were fixed and the orchestrator was rebuilt from
`b4c2e810`. The specification comment rendered as a readable blockquote with the five headings and eight
acceptance criteria as a list, and the plan had two steps ("Add and index the repository glossary", "Add and
index a practical release preparation checklist") without a Revise. The item was left waiting at its plan gate;
its build is not part of this proof.

## What the proof found

Two defects and one gap, the defects fixed on PR #287 with a test that failed first in CI (`ae2f3636`, run
40410108632), then the fix (`0af02f04`, `b4c2e810`, run 40412754229). Two mutants of the fixes were raised as
their own pull requests, #341 and #342, and each was killed in CI.

1. **The specification comment rendered as a code block.** The artifact was posted indented, so GitHub showed
   it as grey preformatted text. `MarkdownQuote` now posts the specification and the plan as a Markdown
   blockquote, every line starting with `>`: headings and lists render, images are removed, links become their
   text with the URL in a code span, and a zero-width space after `@`, `#`, `<`, `://`, `www.` and the other
   reference sigils keeps the quoted text from opening a mention, a reference, a tag or a link. Seen rendered
   on #77.
2. **Every plan had one step.** The plan prompt asked for steps without saying what a step is, and the model
   put the whole ticket in one. `WorkDraftPrompts.PLAN_INSTRUCTIONS` now asks for one step per independently
   verifiable outcome, each building on the previous. #77's plan split into two steps on its own; #75 needed a
   Revise before the fix.
3. **Gap, not fixed.** The run row of a step that is not the last keeps the status `awaiting_delivery` and no
   end time (#71 steps 1 and 2, #75 steps 1 and 2): only the last step's run is delivered, and the run's
   status is set by delivery. The item, the step summaries and the verify effects record the steps correctly.
   Recorded in `docs/UNVERIFIED.md`.

## How the build was proven

PR #287 was built in twelve slices, each RED then GREEN in CI, 80 commits in all. Chosen mutations were raised as
draft pull requests #288 to #342 and killed in CI; the three that survived a round had their tests strengthened,
then died. A four-lens review covered the whole branch, and an external review of the pull request found six
defects (step prompts that did not name the summary file, late draft usage accounting, a step dispatched before
its predecessor's summary was acknowledged, a stale draft on the dashboard, a drift notice shown for generated
items, and a claim method too long), all fixed with a test that failed first; it approved at `d5e74bd3`.

## Limits that remain

- **A step that is not the last keeps `awaiting_delivery` on its run row** (gap 3 above).
- **The artifact and the summary pass through the run-worker host's Docker log** (`docs/UNVERIFIED.md`). The run
  worker scrubs the run's secrets before they leave it; the Docker log is not ours to scrub.
- **One model, one harness.** Every run used Codex with `gpt-6-astra` on a seat. Whether another model meets the
  headings, the plan schema and the 48 KiB prompt budget at a useful rate is unobserved; so is a plan that
  exceeds the budget, a specification that fails validation twice (`spec_invalid`), and `maxStepsPerPlan`
  reached.
- **A lost checkpoint** (`checkpoint_missing`), a failed step run opening the verify gate with completed steps
  kept, Retry build from a step, and Revise refused after three per phase are proven against fakes only.
- **A Jira or GitLab ticket comment** carrying a quoted specification is proven against fakes of their markups
  only; no note has been posted to a live GitLab to watch `/close` stay text.
- **A bare commit SHA in a quoted specification is not neutralised**; GitHub links it when it names a commit
  of the repository.
- **The empty step is proven once,** on a step the plan was asked to make empty. An agent that changes nothing
  of its own accord has not been met.
