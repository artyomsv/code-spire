package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.RefSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Rebuilds a held build's checkpoint in a fresh clone from the agent's handoff bundles (M4 verify and the
 * retried build).
 *
 * <p>The bundles are agent-written: each is copied out of reach without following links — the publisher's
 * own path — before git reads it, and nothing here runs a hook, a filter or the agent's config, because the
 * repository is a fresh clone whose remote is already gone. Each bundle is {@code BASE..HEAD} at its moment,
 * and the gated head may be absent from the newest one if the agent rewrote history, so they are read newest
 * first until it resolves.
 */
public final class CheckpointBundles {
    private static final String IMPORT_REF = "refs/spire/checkpoint-import";
    private static final String BUNDLE_NAME = "[0-9]+\\.bundle";

    private CheckpointBundles() {}

    /** Fetch bundles newest first until {@code head} resolves, then hard-reset the checked-out branch to it. */
    public static void importInto(Path workspace, Path handoff, String head, long maxBytes) throws IOException, GitAPIException {
        if (head == null || !head.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("A full checkpoint head is required, was: " + head);
        ObjectId wanted = ObjectId.fromString(head);
        Path store = Files.createTempDirectory("spire-checkpoint-");
        try (Git git = Git.open(workspace.toFile())) {
            for (Path bundle : newestFirst(handoff)) {
                if (git.getRepository().getObjectDatabase().has(wanted)) break;
                fetchQuietly(git, bundle, store, maxBytes);
            }
            if (!git.getRepository().getObjectDatabase().has(wanted)) throw new CheckpointMissingException(head);
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head).call();
            RefUpdate cleanup = git.getRepository().updateRef(IMPORT_REF);
            cleanup.setForceUpdate(true);
            cleanup.delete();
        } finally {
            deleteTree(store);
        }
    }

    /** One unreadable bundle is not the end: an older one may still hold the head. */
    private static void fetchQuietly(Git git, Path bundle, Path store, long maxBytes) {
        try {
            Path copy = PublishRepo.copyOutOfReach(bundle, store, maxBytes);
            String ref = PublishRepo.soleRefOf(copy);
            git.fetch().setRemote(copy.toAbsolutePath().toString()).setRefSpecs(new RefSpec("+" + ref + ":" + IMPORT_REF)).call();
        } catch (IOException | GitAPIException | RuntimeException unreadable) {
            // Skipped deliberately; the head check after the loop names the failure if nothing held it.
        }
    }

    /** Numeric sequence descending; HandoffWatcher reads the same names ascending. */
    static List<Path> newestFirst(Path handoff) throws IOException {
        if (!Files.isDirectory(handoff, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> entries = Files.list(handoff)) {
            return entries.filter(path -> path.getFileName().toString().matches(BUNDLE_NAME))
                    .sorted(Comparator.comparingLong(CheckpointBundles::sequence).reversed())
                    .toList();
        }
    }

    private static long sequence(Path bundle) {
        String name = bundle.getFileName().toString();
        return Long.parseLong(name.substring(0, name.length() - ".bundle".length()));
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
