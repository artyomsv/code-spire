# Jackson annotations couple the pure domain module to a serialization framework

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java`, `spire-contract/src/main/java/dev/codespire/contract/command/ActionCommand.java` |
| Found during | Full-project code review (4-agent) |
| Date | 2026-07-07 |

## Issue

The sealed wire hierarchies carry `@JsonTypeInfo`/`@JsonSubTypes` directly in `spire-contract`,
which CLAUDE.md declares "pure domain — stays free of framework imports". It is
annotations-only (`jackson-annotations`, no runtime databind dependency), but it still couples
the contract to Jackson and forces every consumer of the domain types onto that annotation set.

## Risks

Maintenance coupling: a Jackson major bump or a switch of wire format touches the domain
module; alternative serializers (e.g. Avro for Kafka) would have to work around the
annotations. No runtime security risk (subtype allowlist is closed, `Id.NAME` not `Id.CLASS`).

## Suggested Solutions

1. Move polymorphic type registration into the per-service (de)serializers via
   `ObjectMapper.registerSubtypes(...)` + mix-ins, keeping the wire format identical
   (same type discriminator property and names). Verify with the existing
   `WireFormatRoundTripTest` in spire-gateway plus the orchestrator/worker split tests.
2. Accept the annotations-only coupling and amend the CLAUDE.md convention to say so
   explicitly (cheapest; the current state is deliberate but undocumented).
