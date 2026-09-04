---
name: smallrye-kafka-failure-defaults
description: SmallRye Kafka defaults make an omitted failure-strategy FAIL, not ignore, and ObjectMapperDeserializer throws on bad JSON; both verified against the pinned versions
metadata:
  type: project
---

Two SmallRye/Quarkus Kafka defaults that this project's comments have already got wrong once, both
read out of the pinned artifacts (smallrye-reactive-messaging-kafka 4.34.0, Quarkus 3.38.3):

- **`failure-strategy` defaults to `fail`**, not `ignore`. Omitting it does NOT mean "drop the
  failure"; it means the channel fails on a nack. `ignore` must be written explicitly — the
  orchestrator's `dlq-in` channel already does.
- **`fail-on-deserialization-failure` defaults to `true`** — "report the failure and mark the
  application as unhealthy". `DeserializerWrapper` calls `reportFailure(e, fatal=true)` and rethrows
  a `KafkaException`. The `null` branch it forwards instead exists only when this is set `false`.
- **Quarkus `ObjectMapperDeserializer` does NOT answer null for unreadable JSON.** It returns null
  only when the byte array itself is null (a tombstone); for anything unparseable it wraps the
  `IOException` in a `RuntimeException` and throws. Verified by running it over five payloads.
  A validating record constructor makes this MORE likely, not less: a bad field throws inside
  Jackson and arrives as the same `RuntimeException`.

**Why:** the house never-throw pattern is an OVERRIDE, not an inherited property.
`RunResultDeserializer` and `IntegrationEventDeserializer` each override `deserialize` and catch
`RuntimeException`; a deserializer that only extends the base class and asserts never-throw in its
javadoc is wrong. The services' readiness probe is `/q/health/ready` (chart `_helpers.tpl`), so a
fatal channel failure takes the whole service out of rotation, not just the one feature.

**How to apply:** when a new Kafka channel claims a poison record cannot hurt it, check for BOTH an
explicit `failure-strategy` and an overriding deserializer. Neither is the default.

Related: [[quarkus-websocket-traps]]
