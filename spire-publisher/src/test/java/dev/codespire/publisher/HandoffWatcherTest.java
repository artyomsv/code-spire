package dev.codespire.publisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffWatcherTest {

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(path -> path.getFileName().toString()).toList();
    }

    @Test
    void refusesToReadPastTheBundleCap(@TempDir Path handoff) throws Exception {
        // Each bundle costs a read up to the size cap plus a push, and the set of handled names is
        // agent-chosen. A run that checkpoints without end is looping or attacking the publisher.
        HandoffWatcher watcher = new HandoffWatcher(handoff, 2);
        List<Path> seen = new ArrayList<>();
        Files.writeString(handoff.resolve("1.bundle"), "x");
        Files.writeString(handoff.resolve("2.bundle"), "y");
        watcher.poll(seen::add);
        assertEquals(2, seen.size());

        Files.writeString(handoff.resolve("3.bundle"), "z");
        IllegalStateException refusal = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> watcher.poll(seen::add));
        assertTrue(refusal.getMessage().contains("more than 2 bundles"), refusal.getMessage());
        assertEquals(2, seen.size(), "the bundle past the cap is never handed over");
    }

    @Test
    void seesEachNewBundleExactlyOnce(@TempDir Path handoff) throws Exception {
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        Files.writeString(handoff.resolve("1.bundle"), "x");
        watcher.poll(seen::add);
        watcher.poll(seen::add);                       // nothing new
        Files.writeString(handoff.resolve("2.bundle"), "y");
        watcher.poll(seen::add);

        assertEquals(2, seen.size(), "a bundle already handled must not be handled again");
        assertEquals(List.of("1.bundle", "2.bundle"), names(seen));
    }

    @Test
    void ignoresPartiallyWrittenFiles(@TempDir Path handoff) throws Exception {
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        // The agent writes to a temp name and renames atomically; anything else is mid-write.
        Files.writeString(handoff.resolve("tmp"), "half");
        Files.writeString(handoff.resolve("3.bundle.part"), "half");
        watcher.poll(seen::add);

        assertTrue(seen.isEmpty(), "only *.bundle counts, so a half-written file is never read");
    }

    @Test
    void ignoresTheDoneSentinel(@TempDir Path handoff) throws Exception {
        // The agent's last act is to write this, and it lands in the same directory. Read as a
        // bundle it would be fetched, fail, and produce a spurious failure outcome at the end of
        // every successful run.
        List<Path> seen = new ArrayList<>();
        Files.writeString(handoff.resolve(PublisherMain.DONE_SENTINEL), "");

        new HandoffWatcher(handoff).poll(seen::add);

        assertTrue(seen.isEmpty());
    }

    @Test
    void ordersBundlesByTheirSequenceNumber(@TempDir Path handoff) throws Exception {
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        Files.writeString(handoff.resolve("10.bundle"), "x");
        Files.writeString(handoff.resolve("2.bundle"), "y");
        watcher.poll(seen::add);

        assertEquals("2.bundle", seen.get(0).getFileName().toString(),
                "2 before 10 — lexical order would push the later commit first");
    }

    @Test
    void anUnnumberedNameSortsLastAndIsStillHandledOnce(@TempDir Path handoff) throws Exception {
        // Every filename here is chosen by the agent. An unparseable one is not an error — it just
        // has no place in the sequence — but it must not throw, and must not be replayed forever.
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        Files.writeString(handoff.resolve("whatever.bundle"), "x");
        Files.writeString(handoff.resolve("1.bundle"), "y");
        watcher.poll(seen::add);
        watcher.poll(seen::add);

        assertEquals(List.of("1.bundle", "whatever.bundle"), names(seen));
    }

    @Test
    void aDoubledSuffixIsNotReadAsItsPrefix(@TempDir Path handoff) throws Exception {
        // Stripping every occurrence of ".bundle" rather than one trailing suffix would read
        // "9.bundle.bundle" as sequence 9 and order it before a genuine 10.
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        Files.writeString(handoff.resolve("9.bundle.bundle"), "x");
        Files.writeString(handoff.resolve("10.bundle"), "y");
        watcher.poll(seen::add);

        assertEquals("10.bundle", seen.get(0).getFileName().toString(),
                "the doubled suffix has no sequence, so it sorts last: " + names(seen));
    }

    @Test
    void aBundleThatThrowsIsNotRetriedForever(@TempDir Path handoff) throws Exception {
        // Marked handled BEFORE the callback runs. Otherwise one unreadable bundle is re-fetched
        // and re-pushed on every poll for the life of the run.
        Files.writeString(handoff.resolve("1.bundle"), "x");
        HandoffWatcher watcher = new HandoffWatcher(handoff);
        int[] attempts = {0};

        try {
            watcher.poll(path -> {
                attempts[0]++;
                throw new IllegalStateException("unreadable");
            });
        } catch (IllegalStateException expected) {
            // the caller decides what a bad bundle means; the watcher only refuses to loop on it
        }
        watcher.poll(path -> attempts[0]++);

        assertEquals(1, attempts[0]);
    }

    @Test
    void aDirectoryNamedLikeABundleIsNotABundle(@TempDir Path handoff) throws Exception {
        List<Path> seen = new ArrayList<>();
        Files.createDirectory(handoff.resolve("4.bundle"));

        new HandoffWatcher(handoff).poll(seen::add);

        assertTrue(seen.isEmpty(), "a directory cannot be fetched, and trying would fail per poll");
    }

    @Test
    void anAbsentHandoffDirectoryIsQuiet(@TempDir Path parent) throws Exception {
        // The publisher can start before the agent has created anything. That is not a failure.
        List<Path> seen = new ArrayList<>();

        new HandoffWatcher(parent.resolve("not-yet")).poll(seen::add);

        assertTrue(seen.isEmpty());
    }
}
