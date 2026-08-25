# `PromptInput` is a fourth transport-type name

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Trivial |
| Location | `spire-orchestrator/.../prompt/PromptInput.java`, `.../prompt/PromptResource.java` (`save`, `preview`, `requireBody`) |
| Found during | PR #61 review — final fix wave for `feat/prompt-scope-and-conversation-findings` |
| Date | 2026-08-24 |

## Issue

The house naming rule for a new transport type permits exactly three names: `*Dto` (the default —
request bodies, response bodies, cross-tier carriers), `*View` (read-only projections, narrower than
the entity and read-only on every path), and `*Payload` (RabbitMQ envelope inners only). `*Dto` is the
explicit tiebreaker: "if you can't decide, name it `*Dto` and move on."

`PromptInput` is a plain JAX-RS request body — `PromptResource.save` deserializes it from a `PUT`, and
`PromptResource.preview` deserializes the same shape from a `POST`. It has setters on none of its
fields (it is a record) but it round-trips a mutable request from the wire, which the naming rule's own
`*View` test excludes explicitly ("a type that round-trips back to the wire as a request body is a
`*Dto`, even if its current callers only read it"). It should be `PromptDto`.

The class itself predates this branch (`git log`: introduced in `Add prompt template registry`, an
ancestor of `master`) — this branch only added the `reviewId` field to it (`Accept a review id on the
prompt preview endpoint`), which is why it was not caught by this branch's own review as newly
introduced debt.

## Risks

Cosmetic. `PromptInput` works correctly wherever it is used; nothing about the extra name causes a
runtime defect. The cost is purely to codebase consistency — a reader who has internalized the
`*Dto`/`*View`/`*Payload` rule from every other transport type in the codebase hits one exception with
no distinguishing reason, and every future prompt-payload type risks copying the wrong precedent.

## Suggested Solutions

1. **Rename to `PromptDto`**, in its own commit per the naming rule's explicit Renames clause ("go in
   their own commit, never bundled with feature work... mixed-purpose commits make the rename invisible
   in code review and break `git blame` on unrelated lines"). A rename inside this fix-wave PR — which
   already touches prompt code for unrelated reasons — would violate the very rule it is closing, so it
   was filed instead of fixed here.
2. **Leave it.** The name is stable and the class is small (17 lines); the inconsistency is
   discoverable but not actively misleading once a reader checks the type's actual shape.
