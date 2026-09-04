---
name: entry-is-an-established-transport-type-name
description: dto-naming.md permits only *Dto/*View/*Payload, but this repo already ships DlqEntry, TimelineEntry and ReconciliationEntry — so a new *Entry is house style, not a fourth name
metadata:
  type: project
---

`~/.claude/rules/dto-naming.md` says a new transport type must be `*Dto`, `*View` or `*Payload`
and "don't invent a fourth". Before raising that against an orchestrator record, check the
siblings: `DlqEntry`, `TimelineEntry`, `ReconciliationEntry` and `ChargeLineView` all predate
`master`, so `*Entry` is an established name here for a read-only list row.

**Why:** raising `RunListEntry` (M2, `FactoryRunProjection`) as a dto-naming violation would have
been a false positive — it follows `DlqEntry`, which the same REST surface already returns. The one
genuinely-flagged fourth name in this repo is tracked at
`techdebt/spire-orchestrator/4-1-promptinput-is-a-fourth-transport-type-name.md`, and its argument
turns on `PromptInput` being a **request body** that round-trips to the wire — the case `*View`
explicitly excludes — not merely on the suffix.

**How to apply:** run `git grep -oE "record [A-Za-z]+(View|Dto|Payload|Entry|Request|Response)"`
over the module before flagging a name. Flag only a name with no sibling precedent, or a read-only
`*View` that has grown a write path. See [[no-project-rules-dir-and-no-lint-gate]].
