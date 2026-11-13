package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The fork's tree is hostile input: cloning it must run and fetch nothing the fork chose. */
class WorkspaceCloneHostileForkTest extends SeededOriginTest {

    private static final String PULL_REF = "refs/pull/7/head";

    /** The pushed fork head, and whether the seed could create a symlink. */
    record HostileFork(String head, boolean linked) {
    }

    /** A pushed submodule origin and its commit. */
    record SubRepo(Path origin, ObjectId commit) {
    }

    private SubRepo pushSubmoduleOrigin() throws Exception {
        Path subOrigin = tmp.resolve("sub-origin.git");
        Path subSeed = tmp.resolve("sub-seed");
        Git.init().setBare(true).setDirectory(subOrigin.toFile()).call().close();
        try (Git sub = Git.init().setDirectory(subSeed.toFile()).setInitialBranch("main").call()) {
            Files.writeString(subSeed.resolve("S.md"), "sub");
            sub.add().addFilepattern(".").call();
            ObjectId commit = sub.commit().setAuthor("seed", "seed@factory.invalid").setMessage("sub").call().copy();
            sub.push().setRemote(subOrigin.toUri().toString()).add("refs/heads/main").call();
            return new SubRepo(subOrigin, commit);
        }
    }

    /** The driver is defined only in the seed's config, which is not pushed, so the clone has none. */
    private void defineEvilFilter(Git git, Path marker) throws IOException {
        // clean passes content through (an empty clean would blank the symlink target); smudge leaves the marker.
        String command = "touch " + marker.toUri().getPath() + "; cat";
        git.getRepository().getConfig().setString("filter", "evil", "smudge", command);
        git.getRepository().getConfig().setString("filter", "evil", "clean", "cat");
        git.getRepository().getConfig().save();
    }

    /** Writes the hostile files into the seed; returns whether the symlink could be created. */
    private boolean writeHostileFiles(Path seed, SubRepo sub) throws IOException {
        Files.writeString(seed.resolve(".gitattributes"), "* filter=evil\n");
        Files.writeString(seed.resolve("payload.txt"), "payload");
        boolean linked = true;
        try {
            Files.createSymbolicLink(seed.resolve("link"), Path.of("../outside-target"));
        } catch (IOException | UnsupportedOperationException noPrivilege) {
            linked = false;
        }
        Files.writeString(seed.resolve(".gitmodules"), "[submodule \"sub\"]\n\tpath = sub\n\turl = "
                + sub.origin().toUri() + "\n");
        return linked;
    }

    /** A gitlink entry pointing at a real commit, as a submodule is recorded in the tree. */
    private void addGitlink(Git git, ObjectId subCommit) throws IOException {
        DirCacheEditor editor = git.getRepository().lockDirCache().editor();
        editor.add(new DirCacheEditor.PathEdit("sub") {
            @Override
            public void apply(DirCacheEntry entry) {
                entry.setFileMode(FileMode.GITLINK);
                entry.setObjectId(subCommit);
            }
        });
        editor.commit();
    }

    private HostileFork pushHostileFork(Path marker, SubRepo sub) throws Exception {
        Path seed = tmp.resolve("seed");
        try (Git git = Git.open(seed.toFile())) {
            git.checkout().setCreateBranch(true).setName("evil").setStartPoint(base).call();
            defineEvilFilter(git, marker);
            boolean linked = writeHostileFiles(seed, sub);
            git.add().addFilepattern(".").call();
            addGitlink(git, sub.commit());
            String forkHead = git.commit().setAuthor("seed", "seed@factory.invalid").setMessage("evil").call().name();
            git.push().setRemote(origin).setRefSpecs(new RefSpec(forkHead + ":" + PULL_REF)).call();
            return new HostileFork(forkHead, linked);
        }
    }

    /**
     * A regression tripwire, not a proof: the fork's tree is hostile input, and a fresh clone's config defines no
     * driver, so an undefined gitattributes {@code filter=} must run nothing (a driver in host config is not
     * covered); a gitlink and a .gitmodules entry must clone nothing; a symlink stays a link.
     */
    @Test
    void aHostileForkTreeRunsNothingInAFreshClone() throws Exception {
        Path marker = tmp.resolve("marker.txt");
        Path outside = tmp.resolve("outside-target");
        Files.writeString(outside, "untouched");
        HostileFork fork = pushHostileFork(marker, pushSubmoduleOrigin());
        Files.deleteIfExists(marker);

        Path ws = tmp.resolve("ws");
        WorkspaceClone.populate(new WorkspaceClone.CloneRequest(origin, fork.head(), "spire/review-7", ws,
                CREDENTIAL, "spire-bot", "spire-bot@factory.invalid", null, PULL_REF));

        assertTrue(Files.exists(ws.resolve("payload.txt")), "the tree was checked out");
        assertFalse(Files.exists(marker), "a gitattributes filter driver must run nothing");
        assertFalse(Files.exists(ws.resolve(".git/modules")), "a submodule must not be cloned");
        assertFalse(Files.exists(ws.resolve("sub/S.md")), "a submodule must not be populated");
        assertEquals("untouched", Files.readString(outside), "nothing outside the workspace is written");
        assumeTrue(fork.linked(), "symlinks need a privilege here");
        assertTrue(Files.isSymbolicLink(ws.resolve("link")), "a symlink stays a link");
    }
}
