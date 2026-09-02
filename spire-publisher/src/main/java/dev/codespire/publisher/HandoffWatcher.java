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
 * Finds the bundles the agent has written to the handoff directory, in sequence, each once.
 *
 * <p>Every filename here is chosen by the agent, so nothing about one is trusted: only regular
 * files ending in {@value #SUFFIX} count, an unnumbered name sorts last, and a name is remembered
 * as handled BEFORE its callback runs, so a callback that throws cannot make the same bundle be
 * read again. The set of handled names is bounded by {@link #maxBundles}: past it the run is over,
 * because an agent that writes bundles without end is either looping or attacking the publisher's
 * memory and object store, and each bundle costs a full read up to the size cap plus a push.
 */
public final class HandoffWatcher {

    static final String SUFFIX = ".bundle";

    /** Generous for checkpointing every few minutes over a long run; absurd for anything honest. */
    static final int DEFAULT_MAX_BUNDLES = 500;

    private final Path handoff;

    private final int maxBundles;

    private final Set<String> handled = new HashSet<>();

    public HandoffWatcher(Path handoff) {
        this(handoff, DEFAULT_MAX_BUNDLES);
    }

    public HandoffWatcher(Path handoff, int maxBundles) {
        if (maxBundles < 1) {
            throw new IllegalArgumentException("maxBundles must be positive");
        }
        this.handoff = handoff;
        this.maxBundles = maxBundles;
    }

    /**
     * @throws IllegalStateException once more bundles have appeared than {@link #maxBundles}; the
     *                               publisher reports it and exits rather than read on
     */
    public void poll(Consumer<Path> onNewBundle) throws IOException {
        if (!Files.isDirectory(handoff)) {
            return;
        }
        List<Path> fresh;
        try (Stream<Path> entries = Files.list(handoff)) {
            // Bounded BEFORE it is materialised: a million zero-byte *.bundle names would otherwise
            // fill the heap in this very listing, ahead of the cap the loop below enforces. One past
            // the remaining allowance is enough to know the cap is exceeded.
            fresh = entries
                    .filter(HandoffWatcher::isBundle)
                    .filter(path -> !handled.contains(path.getFileName().toString()))
                    .limit((long) maxBundles - handled.size() + 1)
                    .sorted(Comparator.comparingLong(HandoffWatcher::sequence)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path bundle : fresh) {
            if (handled.size() >= maxBundles) {
                throw new IllegalStateException("the agent wrote more than " + maxBundles
                        + " bundles; refusing to read further — a run that checkpoints without end is "
                        + "looping or attacking the publisher");
            }
            handled.add(bundle.getFileName().toString());
            onNewBundle.accept(bundle);
        }
    }

    private static boolean isBundle(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(SUFFIX) && name.length() > SUFFIX.length() && Files.isRegularFile(path);
    }

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
