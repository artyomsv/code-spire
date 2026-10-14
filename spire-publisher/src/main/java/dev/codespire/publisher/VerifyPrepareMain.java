package dev.codespire.publisher;

import dev.codespire.workspace.CheckpointBundles;
import dev.codespire.workspace.CheckpointMissingException;
import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.WorkspaceClone;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * The verify unit's init ({@code spire-verify-prepare}, M4): a fresh clone of the base, its remote removed,
 * then exactly the checkpoint commit from the kept run's bundles (spec §3.1).
 *
 * <p>Runs in the trusted publisher image and holds the read credential, so it executes nothing the agent
 * wrote: no hook, no agent config, no filter. The check containers that follow run in the agent image with
 * no credential at all.
 *
 * <p>Exit codes: 0 prepared, {@value #CHECKPOINT_MISSING} checkpoint missing, 1 any other failure,
 * {@value #MISCONFIGURED} misconfigured. The runtime reads the code, then the {@code prepared} line's head.
 */
public final class VerifyPrepareMain {
    static final int PREPARED = 0, FAILED = 1, MISCONFIGURED = 2, CHECKPOINT_MISSING = 3;
    private static final String DEFAULT_WORKSPACE_DIR = "/workspace";
    private static final String IDENTITY = "spire-verify";

    private VerifyPrepareMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.getenv(), System.out));
    }

    static int run(Map<String, String> env, PrintStream stdout) {
        String remote, base, head, username, secret;
        Path workspace, handoff;
        long maxBytes;
        try {
            CorporateTransport.apply(env);
            remote = RemoteUri.validated(Env.required(env, "SPIRE_REMOTE_URI"));
            base = Env.required(env, "SPIRE_BASE_COMMIT");
            head = Env.required(env, "SPIRE_CHECKPOINT_HEAD");
            username = Env.required(env, "SPIRE_CLONE_USERNAME");
            secret = Env.required(env, "SPIRE_CLONE_SECRET");
            workspace = Path.of(env.getOrDefault("SPIRE_WORKSPACE_DIR", DEFAULT_WORKSPACE_DIR));
            handoff = Path.of(Env.required(env, "SPIRE_HANDOFF_DIR"));
            maxBytes = Long.parseLong(Env.required(env, "SPIRE_BUNDLE_MAX_BYTES"));
        } catch (RuntimeException misconfigured) {
            new OutcomeWriter(stdout).failed("PUBLISHER_MISCONFIGURED", misconfigured.getMessage());
            return MISCONFIGURED;
        }
        return prepare(remote, base, head, new GitCredential(username, secret), workspace, handoff, maxBytes,
                new OutcomeWriter(stdout, username, secret));
    }

    /** Separate from {@link #run} so a test can use a local origin, which the remote rules refuse. */
    static int prepare(String remote, String base, String head, GitCredential credential, Path workspace, Path handoff,
                       long maxBytes, OutcomeWriter outcome) {
        try {
            WorkspaceClone.populate(remote, base, IDENTITY, workspace, credential, IDENTITY, IDENTITY + "@" + CloneMain.IDENTITY_DOMAIN);
            CheckpointBundles.importInto(workspace, handoff, head, maxBytes);
            outcome.prepared(head);
            return PREPARED;
        } catch (CheckpointMissingException missing) {
            outcome.failed("CHECKPOINT_MISSING", missing.getMessage());
            return CHECKPOINT_MISSING;
        } catch (IllegalStateException | IllegalArgumentException | IOException | GitAPIException | JGitInternalException failed) {
            // A filter driver the tree asks for (LFS) surfaces here as a checkout failure: verify could not run.
            outcome.failed("VERIFY_PREPARE_FAILED", failed.getClass().getSimpleName() + ": " + failed.getMessage());
            return FAILED;
        }
    }
}
