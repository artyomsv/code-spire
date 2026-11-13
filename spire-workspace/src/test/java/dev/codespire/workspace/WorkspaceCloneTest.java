package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCloneTest {

    private static final GitCredential CREDENTIAL = new GitCredential("spire-bot", "TEST-secret");

    @TempDir
    Path tmp;

    private String origin;

    private String base;

    private String head;

    /** JGit's pack windows outlive Git.close(); on Windows that blocks @TempDir's cleanup. */
    @AfterEach
    void releasePackWindows() {
        PublishRepo.releaseAllPackWindows();
    }

    @BeforeEach
    void seedOrigin() throws Exception {
        Path bare = tmp.resolve("origin.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
        Path seed = tmp.resolve("seed");
        try (Git git = Git.init().setDirectory(seed.toFile()).setInitialBranch("main").call()) {
            Files.writeString(seed.resolve("README.md"), "seed\n");
            git.add().addFilepattern(".").call();
            base = git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("base").call().name();
            Files.writeString(seed.resolve("LATER.md"), "later\n");
            git.add().addFilepattern(".").call();
            head = git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("head").call().name();
            git.push().setRemote(bare.toUri().toString()).add("refs/heads/main").call();
        }
        origin = bare.toUri().toString();
    }

    @Test
    void theWorkspaceStartsAtTheBaseCommitOnTheNewBranchNotAtTheRemotesHead() throws Exception {
        Path ws = tmp.resolve("ws");

        WorkspaceClone.populate(origin, base, "spire/x", ws, CREDENTIAL, "spire-bot", "spire-bot@factory.invalid");

        try (Git git = Git.open(ws.toFile())) {
            Repository repo = git.getRepository();
            assertEquals("refs/heads/spire/x", repo.getFullBranch());
            assertEquals(base, repo.resolve(Constants.HEAD).name());
        }
        assertTrue(Files.exists(ws.resolve("README.md")));
        assertFalse(Files.exists(ws.resolve("LATER.md")), "the head's later commit must not be checked out");
    }

    @Test
    void anAbbreviatedBaseCommitResolves() throws Exception {
        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(origin, base.substring(0, 7), "spire/x", ws, CREDENTIAL, "spire-bot",
                "spire-bot@factory.invalid");
        try (Git git = Git.open(ws.toFile())) {
            assertEquals(base, git.getRepository().resolve(Constants.HEAD).name());
        }
    }

    @Test
    void everyCommitTheAgentMakesIsAuthoredByTheMachineAccount() throws Exception {
        // The identity lives in the repository's config, so neither the image nor the harness has
        // to know the account's name — an autosave commit gets it too.
        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(origin, base, "spire/x", ws, CREDENTIAL, "spire-bot", "spire-bot@factory.invalid");

        try (Git git = Git.open(ws.toFile())) {
            Files.writeString(ws.resolve("NEW.md"), "new\n");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("agent").call();
            assertEquals("spire-bot", commit.getAuthorIdent().getName());
            assertEquals("spire-bot@factory.invalid", commit.getCommitterIdent().getEmailAddress());
        }
    }

    @Test
    void theRemoteIsGoneSoNothingInTheWorkspacePointsAtTheForge() throws Exception {
        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(origin, base, "spire/x", ws, CREDENTIAL, "spire-bot", "spire-bot@factory.invalid");

        try (Git git = Git.open(ws.toFile())) {
            assertTrue(git.remoteList().call().isEmpty());
            assertFalse(git.getRepository().getConfig().toText().contains(origin),
                    "the remote URL must not survive in the workspace's config");
        }
    }

    /**
     * A fix run (ADR-040) clones at the PULL REQUEST's own head, which is not on the default branch.
     *
     * <p>This is the case every other test here misses. They all seed a default branch whose later
     * commit only ADDS a file, so no path exists in both the default branch's tree and the target's
     * with different content — and that is the only shape that ever conflicted. A pull request that
     * EDITS a file produces exactly that path, and until this test the first one to try it failed in
     * production: the init container exited 1 with {@code CLONE_FAILED}, naming the edited file and
     * not the added one.
     *
     * <p>Asserted on the CONTENT, not merely on the absence of a throw: forcing the checkout without
     * populating the work tree from the target would be a pass here and an empty workspace in the
     * run unit.
     */
    @Test
    void aPullRequestBranchThatEditsAFileCheckoutsOutCleanly() throws Exception {
        // The fixture carries no trailing newline on purpose: this suite runs on Windows too,
        // where git's autocrlf rewrites a checked-out "\n" to "\r\n" — which would fail the content
        // assertions below for a reason that has nothing to do with the checkout under test.
        Path bare = tmp.resolve("pr-origin.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
        String prHead;
        Path seed = tmp.resolve("pr-seed");
        try (Git git = Git.init().setDirectory(seed.toFile()).setInitialBranch("main").call()) {
            Files.writeString(seed.resolve("Edited.md"), "on main");
            git.add().addFilepattern(".").call();
            git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("base").call();
            git.push().setRemote(bare.toUri().toString()).add("refs/heads/main").call();

            git.checkout().setCreateBranch(true).setName("feat/edit").call();
            Files.writeString(seed.resolve("Edited.md"), "changed by the pull request");
            Files.writeString(seed.resolve("Added.md"), "added by the pull request");
            git.add().addFilepattern(".").call();
            prHead = git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("pr").call().name();
            git.push().setRemote(bare.toUri().toString()).add("refs/heads/feat/edit").call();
        }

        Path ws = tmp.resolve("pr-ws");
        WorkspaceClone.populate(bare.toUri().toString(), prHead, "feat/edit", ws, CREDENTIAL,
                "spire-bot", "spire-bot@factory.invalid");

        assertEquals("changed by the pull request", Files.readString(ws.resolve("Edited.md")));
        assertEquals("added by the pull request", Files.readString(ws.resolve("Added.md")));
        try (Git git = Git.open(ws.toFile())) {
            assertEquals("refs/heads/feat/edit", git.getRepository().getFullBranch());
            assertEquals(prHead, git.getRepository().resolve(Constants.HEAD).name());
            assertTrue(git.status().call().isClean(), "the workspace must start clean, or the "
                    + "publisher's first diff would report the checkout as the agent's work");
        }
    }

    @Test
    void aBaseTheRemoteDoesNotHoldIsRefused() {
        Path ws = tmp.resolve("ws");
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(origin, "0123456789abcdef0123456789abcdef01234567", "spire/x", ws,
                        CREDENTIAL, "spire-bot", "spire-bot@factory.invalid"));
        assertTrue(refusal.getMessage().contains("not reachable"), refusal.getMessage());
    }

    private static final String PULL_REF = "refs/pull/7/head";

    /** Commits a file on a side branch of the seed and pushes it ONLY as the given ref, never as a branch. */
    private String pushToPullRef(Path seed, String file, String content, String ref) throws Exception {
        try (Git git = Git.open(seed.toFile())) {
            git.checkout().setCreateBranch(true).setName("fork-" + file).setStartPoint(base).call();
            Files.writeString(seed.resolve(file), content);
            git.add().addFilepattern(".").call();
            String commit = git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("fork").call().name();
            git.push().setRemote(origin).setRefSpecs(new RefSpec(commit + ":" + ref)).setForce(true).call();
            return commit;
        }
    }

    @Test
    void aCommitOnlyOnThePullRefIsCheckedOut() throws Exception {
        String forkHead = pushToPullRef(tmp.resolve("seed"), "FORK.md", "fork", PULL_REF);
        Path withoutRef = tmp.resolve("ws-none");
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(origin, forkHead, "spire/review-7", withoutRef, CREDENTIAL, "spire-bot",
                        "spire-bot@factory.invalid"));
        assertTrue(refusal.getMessage().contains("not reachable"), refusal.getMessage());

        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(origin, forkHead, "spire/review-7", ws, CREDENTIAL, "spire-bot",
                "spire-bot@factory.invalid", null, PULL_REF);

        assertEquals("fork", Files.readString(ws.resolve("FORK.md")));
        try (Git git = Git.open(ws.toFile())) {
            assertEquals(forkHead, git.getRepository().resolve(Constants.HEAD).name());
            assertTrue(git.remoteList().call().isEmpty(), "the remote is removed after the fetch");
        }
    }

    @Test
    void aLaggingRefIsRefetched() throws Exception {
        Path seed = tmp.resolve("seed");
        String forkHead = pushToPullRef(seed, "FORK.md", "fork", "refs/pull/8/head");
        // The pull ref holds nothing useful on the first fetch; the forge catches up while init waits.
        pushToPullRef(seed, "OTHER.md", "other", PULL_REF);
        List<Long> sleeps = new ArrayList<>();
        Path ws = tmp.resolve("ws");

        WorkspaceClone.populate(origin, forkHead, "spire/review-7", ws, CREDENTIAL, "spire-bot",
                "spire-bot@factory.invalid", null, PULL_REF, millis -> {
                    sleeps.add(millis);
                    try (Git git = Git.open(seed.toFile())) {
                        git.push().setRemote(origin).setForce(true)
                                .setRefSpecs(new RefSpec(forkHead + ":" + PULL_REF)).call();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        assertEquals(List.of(2000L), sleeps, "one wait, then the second fetch found it");
        assertTrue(Files.exists(ws.resolve("FORK.md")));
    }

    @Test
    void aRefThatNeverHoldsTheCommitFails() throws Exception {
        Path seed = tmp.resolve("seed");
        String forkHead = pushToPullRef(seed, "FORK.md", "fork", "refs/pull/8/head");
        pushToPullRef(seed, "OTHER.md", "other", PULL_REF);
        List<Long> sleeps = new ArrayList<>();
        Path ws = tmp.resolve("ws");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(origin, forkHead, "spire/review-7", ws, CREDENTIAL, "spire-bot",
                        "spire-bot@factory.invalid", null, PULL_REF, sleeps::add));

        assertTrue(refusal.getMessage().contains(forkHead), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(PULL_REF), refusal.getMessage());
        assertEquals(List.of(2000L, 2000L), sleeps, "three fetches in all, two waits between them");
    }

    @Test
    void anInvalidRefIsRefusedBeforeAnyFetch() {
        List<Long> sleeps = new ArrayList<>();
        int index = 0;
        for (String bad : List.of("refs/heads/main", "refs/pull/0/head", "refs/pull/07/head", "refs/pull/7/head\n",
                "refs/pull/7/merge", "refs/pull/7/head:refs/heads/x", "--upload-pack=x", "refs/pull/7/head/extra",
                "refs/pull/1234567890123456789/head", "x refs/pull/7/head", "")) {
            Path ws = tmp.resolve("ws-" + index++);
            // An origin that does not exist: any network call would fail differently from the refusal.
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                    WorkspaceClone.populate(tmp.resolve("missing.git").toUri().toString(), base, "spire/x", ws,
                            CREDENTIAL, "spire-bot", "spire-bot@factory.invalid", null, bad, sleeps::add), bad);
            assertTrue(refusal.getMessage().contains("not a pull request head ref"), bad + ": " + refusal.getMessage());
            assertFalse(Files.exists(ws), "nothing is cloned for " + bad);
        }
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void theBaseIsStillKeptWithAPullRef() throws Exception {
        String forkHead = pushToPullRef(tmp.resolve("seed"), "FORK.md", "fork", PULL_REF);
        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(origin, forkHead, "spire/review-7", ws, CREDENTIAL, "spire-bot",
                "spire-bot@factory.invalid", "main", PULL_REF);

        try (Git git = Git.open(ws.toFile())) {
            assertEquals(head, git.getRepository().resolve(WorkspaceClone.BASE_REF_PREFIX + "main").name());
            assertEquals(forkHead, git.getRepository().resolve(Constants.HEAD).name());
        }
    }

    /**
     * The fork's tree is hostile input. The clone's own config declares no filter driver, so a gitattributes
     * {@code filter=} must run nothing; a gitlink and a .gitmodules entry must clone nothing; a symlink stays a link.
     */
    @Test
    void aGitattributesFilterASubmoduleAndASymlinkRunNothing() throws Exception {
        Path marker = tmp.resolve("marker.txt");
        Path outside = tmp.resolve("outside-target");
        Path subOrigin = tmp.resolve("sub-origin.git");
        Path subSeed = tmp.resolve("sub-seed");
        Git.init().setBare(true).setDirectory(subOrigin.toFile()).call().close();
        ObjectId subCommit;
        try (Git sub = Git.init().setDirectory(subSeed.toFile()).setInitialBranch("main").call()) {
            Files.writeString(subSeed.resolve("S.md"), "sub");
            sub.add().addFilepattern(".").call();
            subCommit = sub.commit().setAuthor("seed", "seed@factory.invalid").setMessage("sub").call().copy();
            sub.push().setRemote(subOrigin.toUri().toString()).add("refs/heads/main").call();
        }
        Path seed = tmp.resolve("seed");
        boolean linked = true;
        String forkHead;
        try (Git git = Git.open(seed.toFile())) {
            git.checkout().setCreateBranch(true).setName("evil").setStartPoint(base).call();
            // The driver is defined only here, in the seed's config; it is not pushed, so the clone has none.
            String command = "touch " + marker.toUri().getPath();
            git.getRepository().getConfig().setString("filter", "evil", "smudge", command);
            git.getRepository().getConfig().setString("filter", "evil", "clean", command);
            git.getRepository().getConfig().save();
            Files.writeString(seed.resolve(".gitattributes"), "* filter=evil\n");
            Files.writeString(seed.resolve("payload.txt"), "payload");
            try {
                Files.createSymbolicLink(seed.resolve("link"), Path.of("../outside-target"));
            } catch (IOException | UnsupportedOperationException noPrivilege) {
                linked = false;
            }
            Files.writeString(seed.resolve(".gitmodules"), "[submodule \"sub\"]\n\tpath = sub\n\turl = "
                    + subOrigin.toUri() + "\n");
            git.add().addFilepattern(".").call();
            // A gitlink entry pointing at a real commit, as a submodule is recorded in the tree.
            DirCacheEditor editor = git.getRepository().lockDirCache().editor();
            editor.add(new DirCacheEditor.PathEdit("sub") {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(FileMode.GITLINK);
                    entry.setObjectId(subCommit);
                }
            });
            editor.commit();
            forkHead = git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("evil").call().name();
            git.push().setRemote(origin).setRefSpecs(new RefSpec(forkHead + ":" + PULL_REF)).call();
        }
        Files.deleteIfExists(marker);

        Path ws = tmp.resolve("ws");
        try { WorkspaceClone.populate(origin, forkHead, "spire/review-7", ws, CREDENTIAL, "spire-bot",
                "spire-bot@factory.invalid", null, PULL_REF); } catch (RuntimeException e) { e.printStackTrace(System.out); throw e; }

        assertTrue(Files.exists(ws.resolve("payload.txt")), "the tree was checked out");
        assertFalse(Files.exists(marker), "a gitattributes filter driver must run nothing");
        assertFalse(Files.exists(ws.resolve(".git/modules")), "a submodule must not be cloned");
        assertFalse(Files.exists(ws.resolve("sub/S.md")), "a submodule must not be populated");
        assertFalse(Files.exists(outside), "nothing outside the workspace is written");
        if (linked) {
            assertTrue(Files.isSymbolicLink(ws.resolve("link")), "a symlink stays a link");
        }
    }
}
