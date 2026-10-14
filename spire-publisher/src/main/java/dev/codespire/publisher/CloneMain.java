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
 * The init container's entrypoint ({@code spire-clone}): populates {@code /workspace} at the base
 * commit on the run's branch, then exits.
 *
 * <p><b>It is meant to hold a READ-only credential and today holds the machine account's one
 * secret</b>, which can also write — {@code Credentials.scm} packs the same value into both slots.
 * The isolation that does hold is the one that matters most: the AGENT gets no git credential,
 * JGit persists none under the workspace, and the remote is removed once the clone is done, so
 * nothing the model can influence ever sees it. The second line of defence — a token that could
 * not push even if it leaked — is not there yet. See {@code docs/UNVERIFIED.md} §E.
 *
 * <p>The commit identity written into the workspace is the clone credential's username; the
 * e-mail is a placeholder under {@value #IDENTITY_DOMAIN} because the machine account's real
 * address is not part of the run command at M0. The forge attributes the push to the account that
 * authenticated it either way.
 */
public final class CloneMain {

    /** The RFC 2606 reserved TLD: unmistakably not a real mailbox. */
    static final String IDENTITY_DOMAIN = "factory.invalid";

    private static final String DEFAULT_WORKSPACE_DIR = "/workspace";

    private CloneMain() {
    }

    /** The default when a retried build names no bundle cap; the run unit always passes one. */
    private static final long DEFAULT_BUNDLE_MAX_BYTES = 256L * 1024 * 1024;

    public static void main(String[] args) {
        int exit = run(System.getenv(), System.out);
        if (exit != 0) System.exit(exit);
    }

    /** @return 0 populated, 1 clone failed, {@value VerifyPrepareMain#CHECKPOINT_MISSING} checkpoint missing */
    static int run(Map<String, String> env, PrintStream stdout) {
        OutcomeWriter outcome = new OutcomeWriter(stdout);
        try {
            // BEFORE the clone, and before anything reads a credential. This is a JVM running
            // JGit, so it honours none of the CA or proxy variables the runtime injects until
            // something turns them into a trust store and a ProxySelector. Without it the clone
            // fails at the forge on every TLS-inspecting network -- the failure FR-F14 exists to
            // remove, and the one the mount test cannot see.
            CorporateTransport.apply(env);
            String remote = RemoteUri.validated(Env.required(env, "SPIRE_REMOTE_URI"));
            String branch = Env.required(env, "SPIRE_BRANCH");
            String base = Env.required(env, "SPIRE_BASE_COMMIT");
            String username = Env.required(env, "SPIRE_CLONE_USERNAME");
            String secret = Env.required(env, "SPIRE_CLONE_SECRET");
            Path workspace = Path.of(env.getOrDefault("SPIRE_WORKSPACE_DIR", DEFAULT_WORKSPACE_DIR));
            outcome = new OutcomeWriter(stdout, username, secret);

            populate(remote, base, branch, workspace, new GitCredential(username, secret), username, checkpointOf(env));
            return 0;
        } catch (CheckpointMissingException missing) {
            outcome.failed("CHECKPOINT_MISSING", missing.getMessage());
            return VerifyPrepareMain.CHECKPOINT_MISSING;
        } catch (IllegalStateException | IllegalArgumentException | IOException | GitAPIException
                 | JGitInternalException e) {
            // Configuration refusals, an unreachable base commit, the filesystem, and the transport:
            // each named, so a new failure mode surfaces as a crash to be classified.
            //
            // JGitInternalException is the classification of one that did. It extends
            // RuntimeException, NOT GitAPIException, so a dirty workspace directory escaped this
            // list entirely: the report was a stack trace on stderr, no JSON line was written, and
            // the run recorded "exit 1" with nothing to say. That is the failure this writer exists
            // to prevent, reached by the one JGit exception that is not part of its API surface.
            outcome.failed("CLONE_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    /**
     * Where a retried build starts (M4): another held run's checkpoint, read from that run's handoff
     * volume mounted read-only, or null to start from the base.
     */
    record Checkpoint(String head, Path bundles, long maxBytes) {}

    private static Checkpoint checkpointOf(Map<String, String> env) {
        String head = env.get("SPIRE_CHECKPOINT_HEAD");
        if (head == null || head.isBlank()) return null;
        String cap = env.get("SPIRE_BUNDLE_MAX_BYTES");
        return new Checkpoint(head, Path.of(Env.required(env, "SPIRE_CHECKPOINT_DIR")),
                cap == null || cap.isBlank() ? DEFAULT_BUNDLE_MAX_BYTES : Long.parseLong(cap));
    }

    /** Separate from {@link #run} so a test can use a local origin, which the remote rules refuse. */
    static void populate(String remote, String base, String branch, Path workspace, GitCredential credential,
                         String username, Checkpoint checkpoint) throws IOException, GitAPIException {
        WorkspaceClone.populate(remote, base, branch, workspace, credential, username, username + "@" + IDENTITY_DOMAIN);
        if (checkpoint != null) CheckpointBundles.importInto(workspace, checkpoint.bundles(), checkpoint.head(), checkpoint.maxBytes());
    }
}
