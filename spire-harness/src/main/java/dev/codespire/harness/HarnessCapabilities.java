package dev.codespire.harness;

/** What an adapter can do. The domain reads these; it never branches on {@link HarnessType}. */
public record HarnessCapabilities(boolean streaming, boolean cancel, boolean steer,
                                  boolean resume, boolean structuredOutput) {
}
