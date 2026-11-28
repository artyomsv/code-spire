package dev.codespire.publisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@code spire-publish-none}: the publisher of a specification or plan run (M4 slice 3, A1, decision 4).
 *
 * <p>It waits for {@code DONE}, reports the agent's artifact and step summary, and publishes nothing. It holds no
 * write credential, has no clone and never calls {@code pushRef}, so even a wrong code path here has nothing to push
 * with and nowhere to push to. A run that committed anyway is reported as {@code repository_changed}: the bundles are
 * counted by name and never read, and the change is discarded with the unit.
 */
final class ReadOnlyPublisher {

    /** Exit code of a stay-mode run told to stop before the agent finished (EX_TEMPFAIL). */
    static final int TERM_BEFORE_DONE = 75;

    /** Set by {@link #stayUntilTerm}; null in the Docker mode, which returns at DONE. */
    private CountDownLatch term;

    private final Path handoff;

    private final OutcomeWriter outcome;

    private final long pollMillis;

    /** The unit's artifact cap; a draft's 32 KiB unless the unit names another (harness reviews, step 4). */
    private int artifactMaxBytes = HandoffFiles.ARTIFACT_MAX_BYTES;

    ReadOnlyPublisher(Path handoff, OutcomeWriter outcome, long pollMillis) {
        this.handoff = handoff;
        this.outcome = outcome;
        this.pollMillis = pollMillis;
    }

    ReadOnlyPublisher artifactMaxBytes(int maxBytes) {
        this.artifactMaxBytes = maxBytes;
        return this;
    }

    /**
     * Stay mode for a Kubernetes native sidecar: print the artifact once at DONE, then wait for the latch so the
     * container never exits and is never restarted. TERM before DONE exits {@link #TERM_BEFORE_DONE}.
     */
    ReadOnlyPublisher stayUntilTerm(CountDownLatch termLatch) {
        this.term = termLatch;
        return this;
    }

    /** @return the exit code: always 0 once DONE was seen, since no handoff file can fail the run here */
    int run() throws IOException, InterruptedException {
        if (term == null) {
            while (!doneExists()) {
                Thread.sleep(pollMillis);
            }
        } else if (!awaitDoneOrTerm()) {
            return TERM_BEFORE_DONE;
        }
        long bundles = countBundles();
        if (bundles > 0) {
            outcome.repositoryChanged((int) Math.min(bundles, Integer.MAX_VALUE));
        }
        HandoffReport.artifact(handoff, outcome, artifactMaxBytes);
        HandoffReport.stepSummary(handoff, outcome);
        if (term != null) {
            term.await();
        }
        return 0;
    }

    private boolean doneExists() {
        return Files.exists(handoff.resolve(PublisherMain.DONE_SENTINEL));
    }

    /** @return true when DONE exists; false when TERM came first and a last look found no DONE */
    private boolean awaitDoneOrTerm() throws InterruptedException {
        while (!doneExists()) {
            if (term.await(pollMillis, TimeUnit.MILLISECONDS)) {
                return false;
            }
        }
        return true;
    }

    private long countBundles() throws IOException {
        try (Stream<Path> entries = Files.list(handoff)) {
            return entries.filter(ReadOnlyPublisher::isBundle).count();
        }
    }

    private static boolean isBundle(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(HandoffWatcher.SUFFIX) && name.length() > HandoffWatcher.SUFFIX.length()
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }
}
