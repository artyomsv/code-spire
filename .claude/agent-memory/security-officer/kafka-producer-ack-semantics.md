---
name: kafka-producer-ack-semantics
description: Verified facts about this repo's Kafka producer path (SmallRye acks default "1", which exceptions are RetriableException in kafka-clients 4.2.1, how to check via javap) for any review that reasons about "did the record land"
metadata:
  type: project
---

Facts verified 2026-09-03 by `javap` against the Gradle cache (kafka-clients 4.2.1,
smallrye-reactive-messaging-kafka 4.34.0), for reviews of `KafkaSends` / `BrokerAckFailure` /
any "ack-before-2xx" reasoning.

- **SmallRye's outgoing `acks` default is `"1"`** (`KafkaConnectorOutgoingConfiguration.getAcks()`
  → `orElse("1")`), and `retries` default is `2147483647`. None of the orchestrator's outgoing
  channels set `acks`, so every publish is leader-only acked and the Kafka client silently disables
  idempotence (acks != all with `enable.idempotence` unset). A broker ack therefore does NOT mean
  durable, and an internal retry can append the same record twice.
- **Non-retriable exceptions that can still be raised after a batch was sent:** the plain
  `KafkaException("Producer is closed forcefully.")` / `"Producer closed while send in progress"`
  that `RecordAccumulator.abortIncompleteBatches` hands every in-flight batch on producer close, and
  `UnknownServerException` (broker-side fault during append). `cause instanceof RetriableException`
  classifies both as "definitely did not land".
- Hierarchy worth remembering: `NotEnoughReplicasAfterAppendException`, `NotEnoughReplicasException`,
  `CorruptRecordException`, `DisconnectException`, `InvalidMetadataException` (→ `NetworkException`,
  `KafkaStorageException`) are `RetriableException`; `OutOfOrderSequenceException`,
  `UnknownProducerIdException`, `TransactionAbortedException`, `UnknownServerException` are plain
  `ApiException`; Kafka 4.x adds `ApplicationRecoverableException` (`ProducerFencedException`,
  `InvalidProducerEpochException`, `InvalidPidMappingException`) — also NOT retriable.
- Probe recipe (no `strings` on this box): `javap -cp <jar> <class> | grep extends` for hierarchy,
  `javap -v -cp <jar> <class> | grep '= Utf8 '` for constant-pool strings, `javap -c` for a
  getter's default literal.

**Why:** Task 9 of factory M1 (commit 567a125) built FR-F10's "ambiguity is the default" on the
`RetriableException` test; the lead asked for a non-retriable exception raised after append and for
the acks semantics. Both needed evidence from the jars, not recall.

**How to apply:** any review touching `KafkaSends`, `BrokerAckFailure`, a `waitForWriteCompletion`
channel, or an argument of the form "the broker acknowledged it, so it landed".

Related: [[postgres-constraint-name-probe]], [[semgrep-on-windows-git-bash]]
