package dev.codespire.harness;

import java.util.List;
import java.util.Objects;

/** What the adapter saw across a whole run, handed back for classification and usage extraction. */
public record RunEventSummary(List<RunEvent> events, boolean sawAnyOutput) {

    public RunEventSummary {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    public static RunEventSummary of(List<RunEvent> events) {
        return new RunEventSummary(events, events.stream().anyMatch(e -> e instanceof RunEvent.Output));
    }
}
