package dev.codespire.runtime;

import java.util.Objects;

/** An opaque handle to a live run unit. {@code providerRunId} is a pod name or a docker unit id. */
public record RunHandle(String runId, String providerRunId) {

    public RunHandle {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(providerRunId, "providerRunId");
    }
}
