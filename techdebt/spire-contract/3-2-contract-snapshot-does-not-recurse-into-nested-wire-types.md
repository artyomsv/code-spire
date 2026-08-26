# The contract snapshot records nested wire types by name only, so reshaping one is invisible

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-contract/src/test/java/dev/codespire/contract/ContractSchemaSnapshotTest.java:153-155`, `spire-contract/src/test/resources/contract-schema.txt` |
| Found during | ADR-023 LLM cost accounting — reshaping `ModelUsage` |
| Date | 2026-08-07 |

## Issue

The ADR-013 compat gate snapshots each sealed wire hierarchy's subtypes and their components. The
component renderer is:

```java
private static String render(RecordComponent component) {
    return component.getName() + ": " + component.getGenericType().getTypeName();
}
```

It records the component's **declared type name** and stops. It never recurses into that type. So the
golden file contains:

```
ReviewGenerated(reviewId: java.lang.String, prId: long, commit: java.lang.String,
                result: dev.codespire.contract.review.ReviewResult, ...,
                reconcileUsage: dev.codespire.contract.review.ModelUsage)
```

`ModelUsage`'s own components are nowhere in the snapshot. The gate therefore guards only the
**outermost** record of each hierarchy. Every nested wire type is opaque to it — `ModelUsage`,
`ReviewResult`, `Finding`, `FindingVerdict`, `ContextItem`, `PriorRun`, `ThreadRef`, `PrCoordinates`,
and the rest.

This was demonstrated, not theorised. ADR-023 removed a component from `ModelUsage` and changed its
remaining shape entirely — a change to a type carried inside `ReviewGenerated`, `FollowUpGenerated` and
`ReviewResult` — and `ContractSchemaSnapshotTest` stayed green with the golden file untouched. The
implementation plan predicted the snapshot would need regenerating; it did not, because the gate could
not see the change.

Note the irony worth preserving: this same file argues, at line 106, that *"a guard that cannot fail is
worse than no guard, because it reads like coverage."* It says so about `@JsonSubTypes` discovery, which
it fixed. The nested-type blind spot is the same failure class, one level down.

## Risks

The gate exists so a breaking wire change cannot ship without a deliberate `eventVersion` bump and an
upcaster (ADR-013). For nested types it provides **no signal at all**, so that decision never gets
forced.

The concrete failure: someone renames or removes a component of `Finding` or `ReviewResult`, or reorders
two same-typed components. `spire-contract` compiles, every module's tests pass, the snapshot is green,
and the change ships. In-flight Kafka records written by the previous version then fail to deserialize.
The never-throw deserializers route them to `cs.dlq` rather than killing consumers, so the symptom is
reviews silently not completing — diagnosable only by reading dead-letter payloads.

Short Kafka retention (ADR-014) bounds the blast radius to in-flight records, which is why this is
Medium rather than High. The event store is unaffected while `DomainEvent` keeps no nested wire types of
its own — a property nothing currently enforces, and worth asserting if this is fixed.

## Correction (2026-08-26): one nested type's invisibility is total, not partial

The title and the description above say the snapshot "does not recurse into nested wire types",
which reads as partial opacity — the outermost shape is still checked, only the nesting inside is
not. For `ContextRequest` that is too generous: it is not a permitted subtype of `ROOTS`
(`IntegrationEvent`, `DomainEvent`, `ActionCommand`) and it is reachable only as a never-recursed
field on `IntegrationEvent.ContextRequested(ContextRequest request)`. The renderer prints
`request: dev.codespire.contract.review.ContextRequest` for that component and nothing else, so the
golden file's line for `ContextRequested` is identical whether `ContextRequest` carries three fields
or thirty.

This was demonstrated again during the repository knowledge base (ADR-026, rung 1): `ContextRequest`
gained a `codeReferences` field to carry diff-derived identifiers and changed paths through to
`CodeContextProvider`. `ContractSchemaSnapshotTest` stayed green with `contract-schema.txt`
byte-identical — not merely unregenerated, unchanged, because nothing in the rendering path could
have detected the addition. A breaking change to `ContextRequest` (a rename, a removed component)
would pass the same gate leaving no trace, exactly as `ModelUsage`'s reshape did for the general
case this entry already documents. `ContextRequest` is not a special case of the existing issue; it
is the sharpest illustration of it, because for this one type there is no partial signal to fall back
on at all.

## Suggested Solutions

1. **Recurse the renderer** (the intended fix). Render a component's type inline when it is a record in
   the `dev.codespire.contract` package, and by name otherwise, so the snapshot spans the full reachable
   wire shape. Needs a visited-set to survive any future self-referential type, and a decision on
   `List<T>`/`Set<T>`/`Map<K,V>` element types — those are where the interesting nesting actually lives
   (`List<Finding>`, `List<TokenCount>`). Regenerate the golden once, deliberately, in its own commit so
   the diff is reviewable rather than mixed into a feature change.
2. **Assert the boundary instead**: fail the build when any component of a Kafka wire record has a type
   that is a `dev.codespire.contract` record not itself listed in the snapshot. Cheaper than full
   recursion and it converts the blind spot into an explicit, enumerated allowlist — the idiom
   `CoreIsProviderNeutralTest` already uses.
3. Leave it, and rely on reviewers noticing nested changes. This is the current state; it is what let a
   reshape of a nested wire type pass green, so it is defensible only for as long as nobody trusts the
   gate to mean more than it does.
