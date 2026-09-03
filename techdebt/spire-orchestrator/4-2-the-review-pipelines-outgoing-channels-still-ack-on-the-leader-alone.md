# Three outgoing channels still acknowledge on the leader alone

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-orchestrator/src/main/resources/application.yml` — `integration-out`, `commands-out`, `events-out`; and the gateway's and workers' own outgoing channels |
| Found during | M1 Task 9 four-lens review (security) |
| Date | 2026-09-03 |

## Issue

SmallRye's outgoing Kafka connector defaults `acks` to `1` — the partition leader alone — and none of
these channels overrides it. Verified by disassembling `KafkaConnectorOutgoingConfiguration.getAcks()`
in the shipped connector rather than read from documentation.

Two consequences. The Kafka client disables producer idempotence whenever `acks` is not `all`, so an
internal producer retry can append the same record twice. And a leader-only acknowledgement can be
lost on failover **after** the caller has already treated the send as successful — which for
`KafkaSends.sendAndAwait` means after it returned, and for the REST paths means after a 2xx.

The factory's two run channels were set to `acks: all` in M1 Task 9, because FR-F10's entire
guarantee is about what an acknowledgement means and the fail-closed reasoning is worthless if the
acknowledged branch is not durable either. The same argument applies to the review pipeline; it was
left out of that commit deliberately, because changing the durability of the review path inside a
factory change is the kind of scope creep that makes a bisect useless.

The review path is more forgiving than the factory's: a lost `GenerateReview` costs a review that
never runs rather than money spent twice, and `cs.dlq` plus the retry ladder catch more of it. That
is why this is Low. It is not zero — a review that silently never runs is the failure the ack-await
was added to prevent in the first place.

## Risks

- A record the caller believes is durable is lost on leader failover, and the caller has already
  reported success.
- Producer idempotence is off, so a retry inside the client can duplicate a record; the consumers'
  own idempotency absorbs this today, which means the exposure is invisible until one does not.

## Suggested Solutions

- Set `acks: all` on `integration-out`, `commands-out` and `events-out`, and on the gateway's and
  workers' outgoing channels. One line each.
- Consider whether it belongs in a shared profile rather than per channel — six copies of one
  durability decision is the drift shape this project has been bitten by before.
- Measure the latency cost first on a multi-broker deployment. On the single-broker dev and compose
  stacks `acks=all` and `acks=1` are the same thing, so a local measurement will show nothing and
  must not be read as evidence that it is free.
