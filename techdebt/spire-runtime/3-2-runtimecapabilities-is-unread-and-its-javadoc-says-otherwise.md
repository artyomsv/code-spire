# `RuntimeCapabilities` is unread in production, and its javadoc claims the domain reads it

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-runtime/src/main/java/dev/codespire/runtime/RuntimeCapabilities.java:4`; `RunRuntime.capabilities()`, `RunRuntime.type()` |
| Found during | PR #96 whole-PR review (code-quality I5) |
| Date | 2026-09-03 |

## Issue

The class javadoc says *"What a runtime can do. The domain reads these; it never branches on
`RuntimeType`."* Neither half has a production reader:

- `RunRuntime.capabilities()` — no production caller (the interface declaration and
  `DockerRunRuntime`'s own implementation only);
- `RunRuntime.type()` — none either;
- none of the six accessors (`networkPolicy`, `resourceLimits`, `steering`, `archival`,
  `garbageCollection`, `nativeSidecar`) is called anywhere.

The harness twin IS read (`RunControlListener` reads `capabilities().steer()`), which is what makes
this one look wired.

Two accessors are worth naming individually:

- **`nativeSidecar`** is documented as existing so a Kubernetes arm can differ — "Kubernetes ≥ 1.29
  terminates a sidecar when the main container exits; absent, the publisher must learn the agent
  finished from a sentinel file". The DONE-sentinel path is implemented **unconditionally**, so a
  K8s arm answering `true` would change nothing.
- **`networkPolicy`** is documented as the switch that makes the model-provider allowlist
  "advisory". Nothing reads it, so nothing is advisory anywhere — while
  `techdebt/spire-runtime-docker/4-3-the-agent-container-on-the-default-bridge-…` records the real
  exposure.

A prior round dismissed `steering` alone with a good reason: the gate is deliberately the harness's
declaration and the runtime's throw is the backstop. That reason does not extend to the other five.

## Risks

A record that looks like a decision point and is not. The next author reads the javadoc, believes
the domain branches on these, and either wires something to a value nothing maintains or leaves a
capability unimplemented believing a caller checks it first.

## Suggested Solutions

1. Give it its one honest reader: the `RunRuntimeContract` proposed in
   `techdebt/spire-runtime/3-3-the-runtime-spi-has-no-conformance-contract.md` is the natural one —
   `assertThrows(UnsupportedOperationException.class, …)` gated on `capabilities().steering()`.
2. Or delete the record and `type()`.
3. At minimum, stop the javadoc claiming a reader it does not have.
