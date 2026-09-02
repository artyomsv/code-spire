package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void aBaseTheRemoteDoesNotHoldIsRefused() {
        Path ws = tmp.resolve("ws");
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () ->
                WorkspaceClone.populate(origin, "0123456789abcdef0123456789abcdef01234567", "spire/x", ws,
                        CREDENTIAL, "spire-bot", "spire-bot@factory.invalid"));
        assertTrue(refusal.getMessage().contains("not reachable"), refusal.getMessage());
    }
}
