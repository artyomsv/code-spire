package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code none} that leaves a harness unconfigured is read as an operator types it: padded or in capitals, it is
 * still {@code none}, never an image called {@code " NONE "} that every dispatch would then fail to pull.
 */
class FactoryConfigNoneSentinelTest {

    private static FactoryConfig withImages(Map<String, String> images) {
        return new FactoryConfig() {
            @Override public Map<String, String> agentImage() { return images; }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 900; }
            @Override public Fix fix() {
                return new Fix() {
                    @Override public Optional<String> harness() { return Optional.empty(); }
                    @Override public Optional<String> model() { return Optional.empty(); }
                };
            }
        };
    }

    @Test
    void aPaddedOrCapitalisedNoneIsNotConfigured() {
        FactoryConfig config = withImages(Map.of("codex", "TEST-codex-image", "claude_code", " NONE ",
                "TEST-other", "None"));

        assertEquals(Map.of("codex", "TEST-codex-image"), config.configuredAgentImages());
    }
}
