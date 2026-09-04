---
name: quarkus-websocket-traps
description: Two Quarkus WebSockets Next traps that make a socket silently unreachable or inert; both proven at runtime in this repo on 2026-09-02
metadata:
  type: project
---

Quarkus WebSockets Next (3.38.3) has two traps this project hit together in `RunTranscriptSocket`,
and neither produces a compile error, a startup warning, or a failing test.

1. **`@WebSocket` path parameters match ONE path segment.** There is no JAX-RS-style `{id:.+}`
   greedy regex. A path template whose parameter must hold a value containing `/` answers **404**
   on the handshake. Verified: `/api/ws/runs/{runId}/transcript` connected for `noslashesatall`
   and was refused 404 for `run::github:acme/app:probe:1`. `RunIds.of` always builds
   `workspace + "/" + slug`, so every real id has a slash.
2. **`OpenConnections.findByEndpointId` wants the FULLY QUALIFIED class name.** The deployment
   processor falls back to `classInfo.name().toString()` when `@WebSocket(endpointId=...)` is
   absent, and the lookup is an exact `equals` on `connection.endpointId()`. Passing
   `getSimpleName()` matches nothing and every push is a silent no-op. Verified:
   `endpointId=[dev.codespire.orchestrator.ws.RunTranscriptSocket]`, simpleName lookup found 0.

**Why:** both fail open and quiet. The snapshot on `@OnOpen` still works, so a tail looks alive
and merely never updates, which reads as a quiet run rather than a broken feature.

**How to apply:** when reviewing or writing a socket here, check the path template against a REAL
identifier value, and prefer the house pattern already in `AttentionBroadcaster` — filter
`connections.stream()` on `handshakeRequest().path()` — over `findByEndpointId`. A live-subscriber
test is the only thing that catches either; see [[websocket-needs-a-live-subscriber-test]].

Related: [[smallrye-kafka-failure-defaults]]
