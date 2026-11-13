package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link HarnessAdapter#parseAll} for an arm that knows only {@link HarnessAdapter#parse}: its one event, or none. */
class HarnessAdapterParseAllTest {

    private static final RunEvent.Output EVENT = new RunEvent.Output(Instant.EPOCH, "TEST-output");

    @Test
    void parseAllDefaultsToParse() {
        HarnessAdapter adapter = new OnlyParse();

        assertEquals(List.of(EVENT), adapter.parseAll("TEST-event"));
        assertEquals(List.of(), adapter.parseAll("TEST-nothing"));
    }

    /** An arm that overrides parse and nothing else of the line reading. */
    private static final class OnlyParse implements HarnessAdapter {

        @Override
        public Optional<RunEvent> parse(String line) {
            return "TEST-event".equals(line) ? Optional.of(EVENT) : Optional.empty();
        }

        @Override
        public HarnessType type() {
            return HarnessType.CODEX;
        }

        @Override
        public HarnessCapabilities capabilities() {
            return new HarnessCapabilities(false, false, false, false, false);
        }

        @Override
        public PromptDelivery promptDelivery() {
            return PromptDelivery.STDIN;
        }

        @Override
        public List<String> command(HarnessInvocation invocation) {
            return List.of("true");
        }

        @Override
        public Map<String, String> environment(HarnessInvocation invocation) {
            return Map.of();
        }

        @Override
        public TerminalOutcome classify(int exitCode, RunEventSummary seen) {
            return TerminalOutcome.success("TEST");
        }

        @Override
        public UsageReport usage(RunEventSummary seen) {
            return UsageReport.unknown();
        }
    }
}
