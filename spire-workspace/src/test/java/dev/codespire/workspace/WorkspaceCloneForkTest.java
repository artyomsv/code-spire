package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fork pull request's head is fetched from the pull ref (refs/pull/N/head) and never from a branch. */
class WorkspaceCloneForkTest extends SeededOriginTest {

    private static final String PULL_REF = "refs/pull/7/head";

    /** The file a seed commit adds, so a commit can be told from another. */
    record ForkFile(String name, String content) {
    }

    private static final ForkFile FORK_MD = new ForkFile("FORK.md", "fork");

    private static final ForkFile OTHER_MD = new ForkFile("OTHER.md", "other");

    private WorkspaceClone.CloneRequest request(String commit, Path ws) {
        return new WorkspaceClone.CloneRequest(origin, commit, "spire/review-7", ws, CREDENTIAL, "spire-bot",
                "spire-bot@factory.invalid", null, PULL_REF);
    }

    private WorkspaceClone.CloneRequest requestKeepingBase(String commit, Path ws) {
        return new WorkspaceClone.CloneRequest(origin, commit, "spire/review-7", ws, CREDENTIAL, "spire-bot",
                "spire-bot@factory.invalid", "main", PULL_REF);
    }

    /** Commits a file on a side branch of the seed and pushes it ONLY as the given ref, never as a branch. */
    private String pushToPullRef(Path seed, ForkFile file, String ref) throws Exception {
        try (Git git = Git.open(seed.toFile())) {
            git.checkout().setCreateBranch(true).setName("fork-" + file.name()).setStartPoint(base).call();
            Files.writeString(seed.resolve(file.name()), file.content());
            git.add().addFilepattern(".").call();
            String commit = git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("fork").call().name();
            git.push().setRemote(origin).setRefSpecs(new RefSpec(commit + ":" + ref)).setForce(true).call();
            return commit;
        }
    }

    /** Pushes the commit to the pull ref the first time init waits, as the forge catching up would. */
    private LongConsumer forgeCatchesUp(Path seed, String forkHead, List<Long> sleeps) {
        return afterWait(seed, sleeps, push -> push.setRefSpecs(new RefSpec(forkHead + ":" + PULL_REF)));
    }

    /** The same, overwriting a pull ref that holds another commit. */
    private LongConsumer forgeOverwrites(Path seed, String forkHead, List<Long> sleeps) {
        return afterWait(seed, sleeps, push -> push.setForce(true)
                .setRefSpecs(new RefSpec(forkHead + ":" + PULL_REF)));
    }

    private LongConsumer afterWait(Path seed, List<Long> sleeps, UnaryOperator<PushCommand> configure) {
        return millis -> {
            sleeps.add(millis);
            try (Git git = Git.open(seed.toFile())) {
                configure.apply(git.push().setRemote(origin)).call();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    @Test
    void aCommitOnlyOnThePullRefIsCheckedOut() throws Exception {
        String forkHead = pushToPullRef(tmp.resolve("seed"), FORK_MD, PULL_REF);
        Path withoutRef = tmp.resolve("ws-none");
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(origin, forkHead, "spire/review-7", withoutRef, CREDENTIAL, "spire-bot",
                        "spire-bot@factory.invalid"));
        assertTrue(refusal.getMessage().contains("not reachable"), refusal.getMessage());

        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(request(forkHead, ws));

        assertEquals("fork", Files.readString(ws.resolve("FORK.md")));
        try (Git git = Git.open(ws.toFile())) {
            assertEquals(forkHead, git.getRepository().resolve(Constants.HEAD).name());
            assertTrue(git.remoteList().call().isEmpty(), "the remote is removed after the fetch");
        }
    }

    @Test
    void aLaggingRefIsRefetched() throws Exception {
        Path seed = tmp.resolve("seed");
        String forkHead = pushToPullRef(seed, FORK_MD, "refs/pull/8/head");
        // The pull ref holds nothing useful on the first fetch; the forge catches up while init waits.
        pushToPullRef(seed, OTHER_MD, PULL_REF);
        List<Long> sleeps = new ArrayList<>();
        Path ws = tmp.resolve("ws");

        WorkspaceClone.populate(request(forkHead, ws), forgeOverwrites(seed, forkHead, sleeps));

        assertEquals(List.of(2000L), sleeps, "one wait, then the second fetch found it");
        assertTrue(Files.exists(ws.resolve("FORK.md")));
    }

    @Test
    void aRefThatNeverHoldsTheCommitFails() throws Exception {
        Path seed = tmp.resolve("seed");
        String forkHead = pushToPullRef(seed, FORK_MD, "refs/pull/8/head");
        pushToPullRef(seed, OTHER_MD, PULL_REF);
        List<Long> sleeps = new ArrayList<>();
        Path ws = tmp.resolve("ws");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(request(forkHead, ws), sleeps::add));

        assertTrue(refusal.getMessage().contains(forkHead), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(PULL_REF), refusal.getMessage());
        assertEquals(List.of(2000L, 2000L), sleeps, "three fetches in all, two waits between them");
    }

    @Test
    void anAbsentRefIsRefetched() throws Exception {
        Path seed = tmp.resolve("seed");
        String forkHead = pushToPullRef(seed, FORK_MD, "refs/pull/8/head");
        List<Long> sleeps = new ArrayList<>();
        Path ws = tmp.resolve("ws");

        // refs/pull/7/head does not exist on the first fetch; the forge creates it while init waits.
        WorkspaceClone.populate(request(forkHead, ws), forgeCatchesUp(seed, forkHead, sleeps));

        assertEquals(List.of(2000L), sleeps);
        assertTrue(Files.exists(ws.resolve("FORK.md")));
    }

    @Test
    void aRefThatNeverExistsFails() throws Exception {
        String forkHead = pushToPullRef(tmp.resolve("seed"), FORK_MD, "refs/pull/8/head");
        List<Long> sleeps = new ArrayList<>();
        Path ws = tmp.resolve("ws");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(request(forkHead, ws), sleeps::add));

        assertTrue(refusal.getMessage().contains(forkHead), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(PULL_REF), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(CREDENTIAL.secret()), refusal.getMessage());
        assertEquals(List.of(2000L, 2000L), sleeps, "three attempts in all");
    }

    private IllegalArgumentException refuseInvalidRef(String bad, Path ws, List<Long> sleeps) {
        // An origin that does not exist: any network call would fail differently from the refusal.
        String missing = tmp.resolve("missing.git").toUri().toString();
        return assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(new WorkspaceClone.CloneRequest(missing, base, "spire/x", ws, CREDENTIAL,
                        "spire-bot", "spire-bot@factory.invalid", null, bad), sleeps::add), bad);
    }

    @Test
    void anInvalidRefIsRefusedBeforeAnyFetch() {
        List<Long> sleeps = new ArrayList<>();
        int index = 0;
        for (String bad : List.of("refs/heads/main", "refs/pull/0/head", "refs/pull/07/head", "refs/pull/7/head\n",
                "refs/pull/7/merge", "refs/pull/7/head:refs/heads/x", "--upload-pack=x", "refs/pull/7/head/extra",
                "refs/pull/1234567890123456789/head", "x refs/pull/7/head", "")) {
            Path ws = tmp.resolve("ws-" + index++);
            IllegalArgumentException refusal = refuseInvalidRef(bad, ws, sleeps);
            assertTrue(refusal.getMessage().contains("not a pull request head ref"),
                    bad + ": " + refusal.getMessage());
            assertFalse(Files.exists(ws), "nothing is cloned for " + bad);
        }
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void theBaseIsStillKeptWithAPullRef() throws Exception {
        String forkHead = pushToPullRef(tmp.resolve("seed"), FORK_MD, PULL_REF);
        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(requestKeepingBase(forkHead, ws));

        try (Git git = Git.open(ws.toFile())) {
            assertEquals(head, git.getRepository().resolve(WorkspaceClone.BASE_REF_PREFIX + "main").name());
            assertEquals(forkHead, git.getRepository().resolve(Constants.HEAD).name());
        }
    }
}
