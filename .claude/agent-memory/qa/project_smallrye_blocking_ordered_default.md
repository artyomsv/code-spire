---
name: smallrye-blocking-ordered-default
description: io.smallrye.common.annotation.Blocking on an @Incoming method is ordered=true in Quarkus, not unordered — the opposite of what the two-annotation naming suggests
metadata:
  type: project
---

`io.smallrye.common.annotation.Blocking` on a `@Incoming` method makes Quarkus call
`setBlockingExecutionOrdered(TRUE)`. Only `io.smallrye.reactive.messaging.annotations.Blocking`
carries an `ordered` attribute, and only `@RunOnVirtualThread` turns ordering off.

**Why:** verified by decompiling
`io/quarkus/smallrye/reactivemessaging/deployment/QuarkusMediatorConfigurationUtil.class`
(quarkus-messaging-deployment 3.38.3): with the reactive-messaging `@Blocking` absent and no
`@RunOnVirtualThread`, the branch at bytecode offset 1021 is `iconst_1;
setBlockingExecutionOrdered`. The two annotations share a simple name, so a class can import the
common one and document itself as unordered while the container serialises every record.

**How to apply:** whenever a listener's javadoc claims records "proceed independently", check which
`Blocking` it imported. `RunControlListener` (spire-run-worker, M1 Task 7) is the live example: its
javadoc says "@Blocking without ordered … letting two cancels for different runs proceed
independently", and it imports the common one, so a hung `runtime.cancel` blocks every later
control record. Nothing in the suite asserts either the annotation type or the channel name — see
[[code-spire-test-gap-pattern]], the wiring-goes-unasserted family.
