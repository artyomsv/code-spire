package dev.codespire.publisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

    private final Path handoff;

    private final OutcomeWriter outcome;

    private final long pollMillis;

    ReadOnlyPublisher(Path handoff, OutcomeWriter outcome, long pollMillis) {
        this.handoff = handoff;
        this.outcome = outcome;
        this.pollMillis = pollMillis;
    }

    /** @return the exit code: always 0 once DONE was seen, since no handoff file can fail the run here */
    int run() throws IOException, InterruptedException {
        while (!Files.exists(handoff.resolve(PublisherMain.DONE_SENTINEL))) {
            Thread.sleep(pollMillis);
        }
        long bundles = countBundles();
        if (bundles > 0) {
            outcome.repositoryChanged((int) Math.min(bundles, Integer.MAX_VALUE));
        }
        HandoffReport.artifact(handoff, outcome);
        HandoffReport.stepSummary(handoff, outcome);
        return 0;
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
