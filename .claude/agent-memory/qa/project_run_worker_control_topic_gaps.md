---
name: run-worker-control-topic-gaps
description: CLOSED 2026-09-03 — MessagingChannelsAreDeclaredTest now asserts the run worker's channel/topic/group/prefetch; keep the pattern for any NEW messaging channel
metadata:
  type: project
---

**Status: closed on PR #96 (verified 2026-09-03).** `spire-run-worker`'s
`MessagingChannelsAreDeclaredTest` now asserts every property this entry was written about: the
`@Incoming` value on both `onControl` and `onCommand`, the `Blocking` annotation *type* and its
`ordered()` value, that the YAML gives control and work different topics
(`cs.run-control` / `cs.run-commands`) AND different consumer groups, and that the control channel
sets `max.poll.records: 1` with no prefetch queue. Kept rather than deleted because the *rule* it
teaches still applies to the next channel anyone adds.

**The original gap, for context:** `RunControlListener` exists so a cancel does not queue behind
the run it cancels, and that property lived entirely in an annotation and a YAML block no test
read — rewriting `@Incoming("run-control-in")` to `@Incoming("run-commands-in")` left all 16
`RunControlListenerTest` cases green, because every one called `onControl(...)` directly.

**How to apply:** when reviewing anything that adds or moves a messaging channel in this repo, ask
for a declaration test in the `MessagingChannelsAreDeclaredTest` / `ScheduledWorkIsDeclaredTest`
mould — assert the `@Incoming` value, the `Blocking` annotation type (see
[[smallrye-blocking-ordered-default]]), and that the YAML gives the channel a different topic and
group from the channel it was split away from. Also check `DlqTopics` in spire-orchestrator: it
maps a dead-lettered record's `type` back to a topic by hand, so a new run command type is silently
routed to `cs.commands` unless it is added there.
