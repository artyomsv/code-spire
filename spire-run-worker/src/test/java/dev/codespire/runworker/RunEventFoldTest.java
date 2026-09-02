package dev.codespire.runworker;

import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventFoldTest {

    private static RunEvent.Usage usage(long total) {
        return new RunEvent.Usage(Instant.EPOCH, UsageReport.of(Map.of(TokenBucket.INPUT, total)));
    }

    @Test
    void keepsWhatTheAdaptersReadAndNothingElse() {
        RunEventFold fold = new RunEventFold();
        fold.accept(new RunEvent.Thinking(Instant.EPOCH, "hm"));
        fold.accept(new RunEvent.ToolUse(Instant.EPOCH, "shell", "ls"));
        fold.accept(new RunEvent.Output(Instant.EPOCH, "done"));
        fold.accept(usage(10));
        fold.accept(usage(25));

        RunEventSummary summary = fold.summary();
        assertTrue(summary.sawAnyOutput());
        assertEquals(2, summary.events().size(), "only the usage events are carried");
        assertEquals(2, fold.dropped());
    }

    @Test
    void aStreamWithNoOutputSaysSo() {
        RunEventFold fold = new RunEventFold();
        fold.accept(new RunEvent.ToolUse(Instant.EPOCH, "shell", "ls"));
        assertFalse(fold.summary().sawAnyOutput());
    }

    @Test
    void theUsageBoundKeepsTheFirstReportsAndAlwaysTheLatest() {
        // The usage reading compares later totals against earlier ones, so a bounded fold must keep
        // both ends: the first reports, and whatever came last. A stream that exceeds the bound is
        // not a harness reporting once per turn; it is the agent writing to the same stdout.
        RunEventFold fold = new RunEventFold();
        for (int i = 0; i < RunEventFold.MAX_USAGE_EVENTS + 50; i++) {
            fold.accept(usage(i));
        }
        RunEvent.Usage last = usage(999_999);
        fold.accept(last);

        RunEventSummary summary = fold.summary();
        assertEquals(RunEventFold.MAX_USAGE_EVENTS + 1, summary.events().size());
        assertSame(last, summary.events().get(summary.events().size() - 1));
        assertEquals(51, fold.dropped());
    }
}
