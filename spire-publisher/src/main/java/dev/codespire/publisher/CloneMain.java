package dev.codespire.publisher;

import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.WorkspaceClone;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
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

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        OutcomeWriter outcome = new OutcomeWriter();
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
            outcome = new OutcomeWriter(System.out, username, secret);

            WorkspaceClone.populate(remote, base, branch, workspace, new GitCredential(username, secret),
                    username, username + "@" + IDENTITY_DOMAIN);
        } catch (IllegalStateException | IllegalArgumentException | IOException | GitAPIException e) {
            // Configuration refusals, an unreachable base commit, the filesystem, and the transport:
            // each named, so a new failure mode surfaces as a crash to be classified.
            outcome.failed("CLONE_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
