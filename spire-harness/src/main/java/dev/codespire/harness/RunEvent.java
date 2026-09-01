package dev.codespire.harness;

import java.time.Instant;

/**
 * The normalized run-event vocabulary. High-volume and deliberately NOT in spire-contract: most of
 * these never reach the durable domain log (ADR-033), and putting them in the contract module would
 * imply a durability guarantee this tier does not have.
 */
public sealed interface RunEvent {

    Instant at();

    record Thinking(Instant at, String text) implements RunEvent {}

    record ToolUse(Instant at, String tool, String summary) implements RunEvent {}

    record ToolResult(Instant at, String tool, boolean error, String summary) implements RunEvent {}

    record Output(Instant at, String text) implements RunEvent {}

    record StateChange(Instant at, String state, String detail) implements RunEvent {}

    record Usage(Instant at, UsageReport report) implements RunEvent {}
}
