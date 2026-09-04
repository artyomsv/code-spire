---
name: websockets-next-path-and-endpoint-ids
description: Quarkus WebSockets Next path params match ONE segment and endpoint ids default to the FQCN — both collide with this project's slash-bearing runId/reviewId keys
metadata:
  type: reference
---

Two Quarkus WebSockets Next behaviours, verified against the 3.38.3 jars, that this repo's id
format collides with:

- `@WebSocket(path = "/x/{p}")` is translated to a Vert.x route `:p`
  (`WebSocketProcessor.TRANSLATED_PATH_PARAM_PATTERN = :[a-zA-Z0-9_]+`), and Vert.x `RouteImpl`
  expands `:p` to `(?<p>[^/]+)` — **one path segment**. There is no `{p:.+}` regex syntax as in
  JAX-RS. `RunIds.of` and `ReviewIds` both embed `workspace/slug`, so any id in a socket path
  segment can never match.
- `@WebSocket#endpointId()` defaults to the sentinel `"<<fcqn name>>"`, which the processor
  resolves to `ClassInfo.name().toString()` — the **fully qualified** class name, not the simple
  name. `OpenConnections.findByEndpointId` is an exact `String.equals`, so
  `findByEndpointId(X.class.getSimpleName())` silently matches nothing. Declare
  `endpointId = "..."` explicitly when using `OpenConnections`.

The repo's other four sockets sidestep both by using static paths and filtering on
`handshakeRequest().path()` (`TimelineBroadcaster`, `ReviewProjection.push`).

Relates to [[run-transcript-socket-review]].
