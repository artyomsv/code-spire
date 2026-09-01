package dev.codespire.publisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Watches the handoff directory for bundles the agent has finished writing.
 *
 * <p>Only {@code *.bundle} counts. The agent writes to a temporary name and renames atomically, so
 * any other name is a file mid-write and must never be read (RUN-TOPOLOGY §4.1). The sentinel the
 * agent drops when it finishes is excluded by the same rule.
 *
 * <p>Every filename here is chosen by the agent, so nothing about one is trusted: the sequence is
 * parsed defensively, an unparseable name sorts last rather than throwing, and a name that is not a
 * plain number is still handled exactly once.
 */
public final class HandoffWatcher {

    static final String SUFFIX = ".bundle";

    private final Path handoff;

    private final Set<String> handled = new HashSet<>();

    public HandoffWatcher(Path handoff) {
        this.handoff = handoff;
    }

    /**
     * Hands each bundle not yet seen to {@code onNewBundle}, oldest sequence first.
     *
     * <p>A bundle is marked handled BEFORE the callback runs. A bundle that throws must not be
     * retried on the next poll: the publisher would then re-fetch and re-push the same objects
     * every cycle for the life of the run, turning one bad bundle into an unbounded loop.
     */
    public void poll(Consumer<Path> onNewBundle) throws IOException {
        if (!Files.isDirectory(handoff)) {
            return;
        }
        List<Path> fresh;
        try (Stream<Path> entries = Files.list(handoff)) {
            fresh = entries
                    .filter(HandoffWatcher::isBundle)
                    .filter(path -> !handled.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparingLong(HandoffWatcher::sequence)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path bundle : fresh) {
            handled.add(bundle.getFileName().toString());
            onNewBundle.accept(bundle);
        }
    }

    private static boolean isBundle(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(SUFFIX) && name.length() > SUFFIX.length() && Files.isRegularFile(path);
    }

    /**
     * "2.bundle" before "10.bundle" — lexical order would ship the later commit first.
     *
     * <p>Strips exactly one trailing suffix rather than replacing every occurrence, so
     * {@code 1.bundle.bundle} is not silently read as sequence 1.
     */
    private static long sequence(Path bundle) {
        String name = bundle.getFileName().toString();
        String stem = name.substring(0, name.length() - SUFFIX.length());
        try {
            return Long.parseLong(stem);
        } catch (NumberFormatException e) {
            // An unnumbered name is not an error — it just has no place in the sequence, so it goes
            // last and the tiebreak on filename keeps the order deterministic.
            return Long.MAX_VALUE;
        }
    }
}
