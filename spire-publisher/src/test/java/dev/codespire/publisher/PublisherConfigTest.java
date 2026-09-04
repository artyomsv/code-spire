package dev.codespire.publisher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherConfigTest {

    private static final String SECRET = "TEST-secret-3f9a";

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_REMOTE_URI", "https://github.com/acme/app.git");
        env.put("SPIRE_BRANCH_BASE", "main");
        env.put("SPIRE_BASE_COMMIT", "abc1234");
        env.put("SPIRE_BRANCH", "spire/fix-typo");
        env.put("SPIRE_PROTECTED_PATHS", " docs/**, , infra/*.tf ");
        env.put("SPIRE_BUNDLE_MAX_BYTES", "1048576");
        env.put("SPIRE_GIT_USERNAME", "spire-bot");
        env.put("SPIRE_GIT_SECRET", SECRET);
        return env;
    }

    private static IllegalStateException refusal(Map<String, String> env) {
        return assertThrows(IllegalStateException.class, () -> PublisherConfig.fromEnv(env));
    }

    @Test
    void aCompleteEnvironmentParses() {
        PublisherConfig config = PublisherConfig.fromEnv(env());

        assertEquals("spire/fix-typo", config.branch());
        assertEquals(List.of("docs/**", "infra/*.tf"), config.protectedPaths());
        assertEquals(1048576L, config.bundleMaxBytes());
        assertEquals("spire-bot", config.credential().username());
        assertEquals(Path.of("/handoff"), config.handoffDir());
    }

    @Test
    void theBranchMayNotBeTheBase() {
        // The publisher holds the run unit's only write credential and the gate judges paths, not
        // refs: a command naming the base as the branch would fast-forward it with the agent's work.
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH_BASE", "spire/fix-typo");

        assertTrue(refusal(env).getMessage().contains("SPIRE_BRANCH_BASE"));
    }

    @Test
    void theBranchMustLiveInTheFactoryNamespace() {
        // Names git accepts, refused purely for living outside the namespace.
        //
        // main and master are deliberately NOT in this list any more: ADR-040 made them refused by
        // the trunk floor, which fires first and says something stronger, so asserting the namespace
        // message for them would assert the wrong rule. They have their own case below, covering
        // BOTH modes -- which is more than this loop ever gave them.
        for (String outside : List.of("release/1.0", "spire", "xspire/fix", "spire-fix")) {
            Map<String, String> env = env();
            env.put("SPIRE_BRANCH", outside);
            env.put("SPIRE_BRANCH_BASE", "develop");
            assertTrue(refusal(env).getMessage().contains(PublisherConfig.BRANCH_NAMESPACE), outside);
        }
        // The bare namespace is refused too — by git's own rule first (trailing slash), which is why
        // this one asserts only that it is refused, not which of the two checks said so.
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH", PublisherConfig.BRANCH_NAMESPACE);
        assertTrue(refusal(env).getMessage().contains("SPIRE_BRANCH"));
    }

    @Test
    void aNameGitRefusesIsRefusedHere() {
        for (String invalid : List.of("spire/a..b", "spire/x.lock", "spire/has space", "spire/@{x}")) {
            Map<String, String> env = env();
            env.put("SPIRE_BRANCH", invalid);
            assertTrue(refusal(env).getMessage().contains("SPIRE_BRANCH"), invalid);
        }
    }

    @Test
    void aRemoteUriCarryingACredentialIsRefusedWithoutEchoingIt() {
        // The URI is printed by JGit transport exceptions, which reach the outcome line on stdout.
        Map<String, String> env = env();
        env.put("SPIRE_REMOTE_URI", "https://spire-bot:" + SECRET + "@github.com/acme/app.git");

        String message = refusal(env).getMessage();
        assertTrue(message.contains("userinfo"), message);
        assertFalse(message.contains(SECRET), "the refusal must not quote the value it refuses");
    }

    @Test
    void anUnparseableRemoteUriIsRefusedWithoutEchoingIt() {
        // URISyntaxException quotes its input, so it must be neither chained nor its message reused.
        Map<String, String> env = env();
        env.put("SPIRE_REMOTE_URI", "https://spire-bot:" + SECRET + "@bad host/acme/app.git");

        IllegalStateException refusal = refusal(env);
        assertFalse(refusal.getMessage().contains(SECRET));
        assertNull(refusal.getCause(), "no cause: its message would carry the value");
    }

    @Test
    void aNonHttpRemoteIsRefused() {
        Map<String, String> env = env();
        env.put("SPIRE_REMOTE_URI", "git@github.com:acme/app.git");
        assertTrue(refusal(env).getMessage().contains("SPIRE_REMOTE_URI"));
    }

    @Test
    void plainHttpIsRefusedOutsideTheLocalTrustZone() {
        // The token would cross the network in the clear. Hosted forges are https; a self-managed
        // one reached by NAME must be too. Only loopback and literal private addresses — a
        // container on the daemon's own bridge, a developer's local forge — may be plain http.
        for (String outside : List.of("http://github.com/acme/app.git", "http://gitlab.example.com/a/b.git",
                "http://8.8.8.8/a.git", "http://172.32.0.1/a.git", "http://[2001:db8::1]/a.git")) {
            Map<String, String> env = env();
            env.put("SPIRE_REMOTE_URI", outside);
            assertTrue(refusal(env).getMessage().contains("plain http"), outside);
        }
        for (String inside : List.of("http://localhost:8929/a.git", "http://127.0.0.1/a.git",
                "http://172.17.0.3/app.git", "http://10.1.2.3/a.git", "http://192.168.0.9:3000/a.git",
                "http://[::1]/a.git", "http://[fd00::5]/a.git", "https://github.com/acme/app.git")) {
            Map<String, String> env = env();
            env.put("SPIRE_REMOTE_URI", inside);
            assertEquals(inside, PublisherConfig.fromEnv(env).remoteUri(), inside);
        }
    }

    @Test
    void aGlobTheGateCannotApplyRefusesToStart() {
        // Otherwise the first bundle — after the agent has run and been paid for — is where it fails.
        Map<String, String> env = env();
        env.put("SPIRE_PROTECTED_PATHS", "docs/**, infra/{a,b}.tf");
        assertTrue(refusal(env).getMessage().contains("SPIRE_PROTECTED_PATHS"));
    }

    @Test
    void anUnboundedBundleCapIsRefused() {
        for (String bad : List.of("0", "-1", "lots")) {
            Map<String, String> env = env();
            env.put("SPIRE_BUNDLE_MAX_BYTES", bad);
            assertTrue(refusal(env).getMessage().contains("SPIRE_BUNDLE_MAX_BYTES"), bad);
        }
    }

    @Test
    void aMissingVariableNamesItself() {
        for (String name : env().keySet()) {
            Map<String, String> env = env();
            env.remove(name);
            if (name.equals("SPIRE_PROTECTED_PATHS")) {
                assertEquals(List.of(), PublisherConfig.fromEnv(env).protectedPaths());
                continue;
            }
            assertTrue(refusal(env).getMessage().contains(name), name);
        }
    }

    // --- ADR-040: pushing to a pull request's own source branch ------------------------------

    /**
     * The mode M2 exists for. A fix has to land where the finding lives, because reconciliation is
     * keyed per review — a second pull request is a second review with no prior run, so the finding
     * that caused the work could never resolve.
     *
     * <p>Both floors are lifted TOGETHER and only here: a human's branch is not under {@code spire/},
     * and pushing to the branch we cloned is the whole point.
     */
    @Test
    void existingModePushesToTheBranchItForkedFrom() {
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH_MODE", "existing");
        env.put("SPIRE_BRANCH_BASE", "feature/login");
        env.put("SPIRE_BRANCH", "feature/login");

        PublisherConfig config = PublisherConfig.fromEnv(env);
        assertEquals("feature/login", config.branch());
        assertEquals("feature/login", config.baseBranch());
    }

    /**
     * The default is unchanged, and that is asserted rather than assumed. An absent mode must mean
     * the M0 behaviour: a mode that silently defaulted to {@code existing} would lift the floor for
     * every run ever dispatched.
     */
    @Test
    void anAbsentModeKeepsTheNamespaceFloor() {
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH", "feature/login");

        assertTrue(refusal(env).getMessage().contains(PublisherConfig.BRANCH_NAMESPACE));
    }

    /** An unrecognised mode is refused, not treated as the permissive one. */
    @Test
    void anUnknownModeIsRefusedRatherThanGuessed() {
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH_MODE", "Existing ");
        env.put("SPIRE_BRANCH", "feature/login");
        env.put("SPIRE_BRANCH_BASE", "feature/login");

        assertEquals("feature/login", PublisherConfig.fromEnv(env).branch(),
                "case and surrounding space are operator typing, not a different mode");

        env.put("SPIRE_BRANCH_MODE", "anything-else");
        assertTrue(refusal(env).getMessage().contains("SPIRE_BRANCH_MODE"));
    }

    /**
     * <b>The half that matters, and the half a renamed variable would silently delete.</b>
     *
     * <p>The floor exists because this process holds the only write credential in the run unit and
     * the gate judges paths, not refs — so a command naming the default branch would fast-forward it
     * with whatever the agent produced. Lifting the namespace rule must not lift that.
     */
    @Test
    void existingModeStillRefusesTheRepositoryDefaultBranch() {
        for (String protectedBranch : List.of("main", "master")) {
            Map<String, String> env = env();
            env.put("SPIRE_BRANCH_MODE", "existing");
            env.put("SPIRE_BRANCH_BASE", protectedBranch);
            env.put("SPIRE_BRANCH", protectedBranch);

            assertTrue(refusal(env).getMessage().contains(protectedBranch),
                    "existing mode must not admit " + protectedBranch);
        }
    }

    /**
     * The pull request's DESTINATION branch, refused by name rather than by guessing at conventions.
     *
     * <p>A deployment whose trunk is called {@code develop} or {@code release/2026.1} is not covered
     * by a main/master list, and the orchestrator knows the real answer — it read the pull request.
     * So it passes it, and the publisher refuses it without having to make an API call it must not
     * be able to make.
     */
    @Test
    void existingModeRefusesThePullRequestsDestinationBranch() {
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH_MODE", "existing");
        env.put("SPIRE_BRANCH_BASE", "develop");
        env.put("SPIRE_BRANCH", "develop");
        env.put("SPIRE_PROTECTED_BRANCH", "develop");

        assertTrue(refusal(env).getMessage().contains("develop"));
    }

    /** And it refuses that branch even when it is not the one being forked from. */
    @Test
    void existingModeRefusesTheDestinationBranchEvenWhenItIsNotTheBase() {
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH_MODE", "existing");
        env.put("SPIRE_BRANCH_BASE", "feature/login");
        env.put("SPIRE_BRANCH", "develop");
        env.put("SPIRE_PROTECTED_BRANCH", "develop");

        assertTrue(refusal(env).getMessage().contains("develop"));
    }

    /** A ref git will not accept is still refused in either mode. */
    @Test
    void existingModeStillRefusesAnInvalidRefName() {
        Map<String, String> env = env();
        env.put("SPIRE_BRANCH_MODE", "existing");
        env.put("SPIRE_BRANCH_BASE", "feature/..login");
        env.put("SPIRE_BRANCH", "feature/..login");

        assertTrue(refusal(env).getMessage().contains("SPIRE_BRANCH"));
    }

    /**
     * The trunk floor holds in BOTH modes, which is the property that makes lifting the namespace
     * rule safe. Asserted here rather than only in the existing-mode case, because a floor that only
     * applies to the mode that needs it is not a floor.
     */
    @Test
    void theRepositoryDefaultBranchIsRefusedInEveryMode() {
        for (String mode : List.of("namespace", "existing")) {
            for (String trunk : List.of("main", "master")) {
                Map<String, String> env = env();
                env.put("SPIRE_BRANCH_MODE", mode);
                env.put("SPIRE_BRANCH", trunk);
                env.put("SPIRE_BRANCH_BASE", "develop");
                assertTrue(refusal(env).getMessage().contains(trunk), mode + "/" + trunk);
            }
        }
    }

    /** The default mode is unaffected by a protected branch it was never told about. */
    @Test
    void theNamespaceModeIgnoresTheProtectedBranchVariable() {
        Map<String, String> env = env();
        env.put("SPIRE_PROTECTED_BRANCH", "main");

        assertEquals("spire/fix-typo", PublisherConfig.fromEnv(env).branch());
    }
}
