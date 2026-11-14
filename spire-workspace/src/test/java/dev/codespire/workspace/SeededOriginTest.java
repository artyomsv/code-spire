package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared fixture of the workspace clone tests: a bare origin whose {@code main} holds a base commit and a later
 * head commit, with the paths and commit ids the tests read.
 */
abstract class SeededOriginTest {

    static final GitCredential CREDENTIAL = new GitCredential("spire-bot", "TEST-secret");

    @TempDir
    Path tmp;

    String origin;

    String base;

    String head;

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
}
