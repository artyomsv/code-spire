---
name: quarkus-kafka-and-websockets-next-traps
description: Two verified Quarkus 3.38 library facts that reviews keep needing - ObjectMapperDeserializer throws on bad JSON, and websockets-next endpointId defaults to the FQCN with single-segment path params
metadata:
  type: reference
---

Verified by bytecode (javap) on the 3.38.3 jars in the Gradle cache, 2026-09-02.

- `io.quarkus.kafka.client.serialization.ObjectMapperDeserializer.deserialize` returns null ONLY
  when the byte array is null. Any `IOException` from Jackson (malformed JSON, a record constructor
  that throws) is rethrown as `RuntimeException`. With SmallRye's default
  `fail-on-deserialization-failure=true` and the default `failure-strategy=fail`, that kills the
  channel and the offset is never committed. A subclass must override `deserialize` with a
  try/catch (as `IntegrationEventDeserializer` and `RunResultDeserializer` do). A javadoc that says
  "the base class answers null" is wrong.
- `@WebSocket.endpointId` defaults to the sentinel `<<fcqn name>>`, which the deployment processor
  replaces with the fully qualified class name. `OpenConnections.findByEndpointId(SimpleName)`
  matches nothing. Existing broadcasters in this repo filter on `handshakeRequest().path()` instead.
- websockets-next turns `{param}` into a Vert.x `:param`, which matches ONE path segment. An id
  that contains `/` must be sent `%2F`-encoded or the handshake 404s.

**How to apply:** when a review adds a Kafka deserializer or a `@WebSocket` endpoint, check these
three points before trusting the class javadoc. Related: [[semgrep-on-windows-git-bash]].
