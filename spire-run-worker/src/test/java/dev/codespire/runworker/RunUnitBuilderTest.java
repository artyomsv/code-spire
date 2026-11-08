package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.HostMount;
import dev.codespire.runtime.Mount;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every containment property of the design is decided by this class, so every one of them is
 * asserted here. Nothing downstream can restore a property this builder gives away.
 *
 * <p>The credentials arrive as the orchestrator sends them — Tink ciphertext bound to the run — and
 * are opened with a fresh keyset here, so what the containers receive is asserted on the PLAINTEXT
 * the agent and publisher would actually use, never on the opaque string that rode the bus.
 */
class RunUnitBuilderTest {

    private static final String RUN_ID = "run::github:acme/app:finding-1:1";

    private static final String SCM_LOGIN = "TEST-machine-account";

    private static final String SCM_TOKEN = "TEST-scm-token-do-not-print";

    private static final String HARNESS_KEY = "TEST-harness-key";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    private final RunUnitBuilder builder = builder(encryption);

    private static RunUnitBuilder builder(EncryptionService encryption) {
        Credentials credentials = new Credentials();
        credentials.encryption = encryption;
        credentials.mapper = JSON;
        RunUnitBuilder builder = new RunUnitBuilder();
        builder.credentials = credentials;
        builder.publisherImage = "spire-publisher:TEST";
        builder.maxWallClockSeconds = 3600;
        builder.enterprise = unconfigured();
        builder.egressMode = EgressPolicy.Mode.OFF;
        return builder;
    }

    /**
     * A worker with no corporate configuration, which is the ordinary deployment.
     *
     * <p>The SHARED fake, which answers every accessor the bean has. A double that answers only
     * the method used today is the trap this repository has now hit six times; the shared one is
     * where that lesson lives so it does not have to be relearned per test file.
     */
    private static EnterpriseEnvironmentConfig unconfigured() {
        return RunLauncherTest.noCorporateEnvironment();
    }

    private static EnterpriseEnvironmentConfig corporate(EnterpriseEnvironment environment) {
        return RunLauncherTest.corporate(environment);
    }

    @Test
    void aCommandAskingForMoreWallClockThanTheWorkerBudgetsIsRefusedBeforeAnyContainer() {
        // RunAckBudget sizes the channel's ack threshold to spire.run.max-wall-clock-seconds. A
        // command that carries more would outlive that budget — the poison-pill shape again — so it
        // is a BAD_COMMAND at the builder, where nothing has been created or paid for yet.
        RunCommand.ExecuteRun over = new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1", "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of(), 3601, packedScm(SCM_LOGIN, SCM_TOKEN), null);

        IllegalArgumentException refusal = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> builder.build(over, new CodexAdapter()));
        org.junit.jupiter.api.Assertions.assertTrue(refusal.getMessage().contains("max-wall-clock-seconds"),
                refusal.getMessage());
    }

    @Test
    void thePublisherImageComesFromConfigurationNotACompiledConstant() {
        // A registry prefix or a digest is the deployment's to choose, and both halves of the run
        // unit — agent and publisher — are configured the same way.
        RunUnitSpec unit = builder.build(command(), new CodexAdapter());
        org.junit.jupiter.api.Assertions.assertEquals("spire-publisher:TEST", unit.init().image());
        org.junit.jupiter.api.Assertions.assertEquals("spire-publisher:TEST", unit.publisher().image());
    }

    /** What the orchestrator's packer produces: the account's login and token in one envelope. */
    private String packedScm(String login, String token) {
        try {
            return encryption.encryptString(JSON.writeValueAsString(new MachineAccountCredential(login, token)),
                    RunCommand.scmCredentialAad(RUN_ID));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private RunCommand.ExecuteRun command() {
        return new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1",
                "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of("deploy/**"), 3600,
                packedScm(SCM_LOGIN, SCM_TOKEN),
                encryption.encryptString(HARNESS_KEY, RunCommand.harnessCredentialAad(RUN_ID)));
    }

    @Test
    void theMachineAccountsLoginTravelsInsideTheEnvelopeNotAsAConstant() {
        // The worker used to hardcode "spire-bot" beside every token. The login is whatever account
        // the operator registered, and it rides in the same ciphertext as the token.
        RunUnitSpec unit = unit();

        assertEquals(SCM_LOGIN, unit.init().environment().get("SPIRE_CLONE_USERNAME"));
        assertEquals(SCM_LOGIN, unit.publisher().environment().get("SPIRE_GIT_USERNAME"));
    }

    @Test
    void aBareTokenInTheEnvelopeIsRefusedNotUsedAsACredential() {
        RunCommand.ExecuteRun bare = new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1", "fix it", "codex", "gpt-5.6", "img",
                List.of(), 60, encryption.encryptString(SCM_TOKEN, RunCommand.scmCredentialAad(RUN_ID)), null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> builder.build(bare, new CodexAdapter()));
        assertFalse(refused.getMessage().contains(SCM_TOKEN), "the refusal must not quote the plaintext");
    }

    private RunUnitSpec unit() {
        return builder.build(command(), new CodexAdapter());
    }

    /**
     * A build run says nothing about branch mode, and that ABSENCE is the M0 rule.
     *
     * <p>Asserted rather than assumed: writing the variables unconditionally would be worse than
     * verbose, because a blank {@code SPIRE_PROTECTED_BRANCH} reads to the publisher as "present but
     * blank", which its required-in-existing-mode check treats exactly as absent. The distinction
     * would survive here and be lost there.
     */
    @Test
    void aBuildRunTellsThePublisherNothingAboutBranchMode() {
        Map<String, String> env = unit().publisher().environment();

        // Keys, not the map: the value side holds SPIRE_GIT_SECRET, and an assertion message is
        // printed on failure. A fake token today is still the wrong habit to leave in place.
        assertFalse(env.containsKey("SPIRE_BRANCH_MODE"), env.keySet().toString());
        assertFalse(env.containsKey("SPIRE_PROTECTED_BRANCH"), env.keySet().toString());
    }

    /**
     * A protected branch is honoured OUTSIDE existing mode too, because the publisher honours it
     * there.
     *
     * <p>The command can carry a destination without the mode — nothing refuses that pair — and the
     * builder used to write the variable only in existing mode. So the one direction that matters
     * was the one that broke: a floor the caller asked for was dropped by the caller's own
     * plumbing, silently, and the publisher never learned it had been asked for anything.
     */
    @Test
    void aProtectedBranchIsNotDroppedJustBecauseTheModeIsTheDefault() {
        RunCommand.ExecuteRun original = command();
        // Spelled out rather than withered: a wither for a pair only this test builds would be
        // production API existing for a test, and enumerating here fails to COMPILE if a component
        // is added — which is the signal the rebuild-site trap wants anyway.
        RunCommand.ExecuteRun withFloorOnly = new RunCommand.ExecuteRun(original.runId(),
                original.repo(), original.remoteUri(), original.baseBranch(), original.baseCommit(),
                original.branch(), original.prompt(), original.harness(), original.model(),
                original.agentImage(), original.protectedPaths(), original.maxWallClockSeconds(),
                original.scmCredential(), original.harnessCredential(), false, "develop",
                original.reasoningEffort());

        Map<String, String> env = builder.build(withFloorOnly, new CodexAdapter())
                .publisher().environment();

        assertEquals("develop", env.get("SPIRE_PROTECTED_BRANCH"));
        assertFalse(env.containsKey("SPIRE_BRANCH_MODE"), env.keySet().toString());
    }

    /** A run paid by a sign-in hands the agent the file, never as a key (M3.5 part F). */
    @Test
    void aRunPaidBySignInHandsTheAgentTheFileNotAKey() {
        Map<String, String> env = builder.build(command().paidBySignIn(), new CodexAdapter()).agent().environment();

        assertEquals(HARNESS_KEY, env.get("CODEX_SIGN_IN_FILE"));
        assertFalse(env.containsKey("OPENAI_API_KEY"));
    }

    /** The level on the command is the level in the agent's command line, not lost on the way. */
    @Test
    void theThinkingLevelReachesTheAgentsCommand() {
        List<String> argv = builder.build(command().atEffort("xhigh"), new CodexAdapter()).agent().argv();

        assertTrue(String.join(" ", argv).contains("model_reasoning_effort=\"xhigh\""), argv.toString());
    }

    /** A fix run carries both, because the publisher refuses the mode without the destination. */
    @Test
    void aFixRunTellsThePublisherWhichBranchIsOffLimits() {
        RunUnitSpec unit = builder.build(command().onExistingBranch("develop"), new CodexAdapter());
        Map<String, String> env = unit.publisher().environment();

        assertEquals("existing", env.get("SPIRE_BRANCH_MODE"));
        assertEquals("develop", env.get("SPIRE_PROTECTED_BRANCH"));
    }

    private static Optional<Mount> mount(ContainerSpec container, String volume) {
        return container.mounts().stream().filter(m -> m.volume().equals(volume)).findFirst();
    }

    @Test
    void theAgentGetsNoWriteCredential() {
        RunUnitSpec unit = unit();

        // ADR-039: the agent physically cannot push, gate or no gate. Everything else in the design
        // is a second line of defence behind this one.
        assertFalse(unit.agent().environment().containsKey("SPIRE_GIT_SECRET"));
        assertFalse(unit.agent().environment().containsValue(SCM_TOKEN));
        assertEquals(SCM_TOKEN, unit.publisher().environment().get("SPIRE_GIT_SECRET"));
    }

    @Test
    void thePublisherNeverMountsTheWorkspace() {
        RunUnitSpec unit = unit();

        // It holds the write credential, so it must never reach agent-authored git config or hooks.
        assertTrue(mount(unit.publisher(), RunUnitBuilder.WORKSPACE).isEmpty(),
                "the publisher must not reach agent-controlled git config or hooks");
        assertTrue(mount(unit.agent(), RunUnitBuilder.WORKSPACE).isPresent());
        assertEquals(1, unit.publisher().mounts().size(),
                "handoff is the ONLY volume the publisher sees");
    }

    @Test
    void handoffIsReadOnlyToThePublisherAndWritableByTheAgent() {
        RunUnitSpec unit = unit();

        assertTrue(mount(unit.publisher(), RunUnitBuilder.HANDOFF).orElseThrow().readOnly());
        assertFalse(mount(unit.agent(), RunUnitBuilder.HANDOFF).orElseThrow().readOnly());
    }

    @Test
    void theProtectedPathsReachThePublisherAndNotTheAgent() {
        RunUnitSpec unit = unit();

        // The gate's rules come from the operator side, never from anything the agent can influence.
        // An agent that could read them could shape its work to slip past; one that could write
        // them would not need to.
        assertEquals("deploy/**", unit.publisher().environment().get("SPIRE_PROTECTED_PATHS"));
        assertFalse(unit.agent().environment().containsKey("SPIRE_PROTECTED_PATHS"));
    }

    @Test
    void theInitContainerClonesWithAReadCredentialOnly() {
        RunUnitSpec unit = unit();

        assertEquals(SCM_TOKEN, unit.init().environment().get("SPIRE_CLONE_SECRET"));
        assertFalse(unit.init().environment().containsKey("SPIRE_GIT_SECRET"),
                "the token that can write must not enter the container that prepares agent-reachable disk");
    }

    @Test
    void theAgentGetsTheModelCredentialAndTheWorkspacePath() {
        RunUnitSpec unit = unit();

        assertEquals(HARNESS_KEY, unit.agent().environment().get("OPENAI_API_KEY"));
        // The codex arm's argv is a shell and one line, because `codex login --with-api-key` reads
        // the key from stdin and nothing else avoids the pipe. The workspace and the harness are
        // still both named in it, which is what this test is about; CodexAdapterTest owns the line's
        // shape.
        String line = String.join(" ", unit.agent().argv());
        assertTrue(line.contains("/workspace"), line);
        assertTrue(line.contains("codex exec"), line);
    }

    @Test
    void theCiphertextItselfReachesNoContainer() {
        // What rode the bus is bound to this run and useless anywhere else, but it is still a
        // credential-shaped string: the containers get the opened value or nothing.
        RunCommand.ExecuteRun command = command();
        RunUnitSpec unit = builder.build(command, new CodexAdapter());

        for (ContainerSpec container : List.of(unit.init(), unit.agent(), unit.publisher())) {
            assertFalse(container.environment().containsValue(command.scmCredential()));
            assertFalse(container.environment().containsValue(command.harnessCredential()));
        }
    }

    @Test
    void aCredentialPackedForAnotherRunIsRefused() {
        // The AAD binds the ciphertext to its run. A ciphertext lifted from one command and replayed
        // on another run's command — same workspace, same machine account — does not open.
        RunCommand.ExecuteRun other = new RunCommand.ExecuteRun("run::github:acme/app:finding-2:1",
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_2", "fix it", "codex", "gpt-5.6", "img",
                List.of(), 60, command().scmCredential(), null);

        assertThrows(RuntimeException.class, () -> builder.build(other, new CodexAdapter()));
    }

    @Test
    void thePromptIsNeverInTheAgentsArgv() {
        // The work item is untrusted text and Codex reads it from stdin. If it reached argv here it
        // would be visible in docker inspect and in /proc/<pid>/cmdline, and a body beginning with
        // a hyphen would be parsed as an option.
        RunUnitSpec unit = unit();

        assertFalse(String.join(" ", unit.agent().argv()).contains("fix the typo"));
        // The trailing "-" is the prompt POSITION, telling Codex to read stdin. It is not the
        // prompt, and it is still the last thing on the line now that a shell runs the line.
        assertTrue(unit.agent().argv().getLast().endsWith(" -"), unit.agent().argv().getLast());
    }

    /**
     * The other half of the argv rule, which nothing asserted. The prompt was kept OFF argv and then
     * delivered nowhere: no stdin channel existed, so a STDIN arm read an empty prompt, produced
     * nothing, exited 0 and was reported finished. The agent image's entrypoint contract hands the
     * prompt from SPIRE_PROMPT to the harness on stdin, so that variable is the delivery.
     */
    @Test
    void thePromptReachesTheAgentThroughItsEntrypointContract() {
        RunUnitSpec unit = unit();

        String prompt = unit.agent().environment().get("SPIRE_PROMPT");
        assertTrue(prompt.startsWith("fix the typo"),
                "a STDIN harness receives the prompt through SPIRE_PROMPT, the entrypoint's contract");
        assertTrue(prompt.contains("SPIRE_SUMMARY"),
                "with the commit instruction appended, which is the only layer that asks for a commit");
        assertFalse(unit.publisher().environment().containsKey("SPIRE_PROMPT"),
                "the publisher never sees the work item; it has no use for it and no business with it");
    }

    @Test
    void theTwoBranchesAreCarriedSeparately() {
        // baseBranch is what the publisher CLONES; branch is what it PUSHES to. One name for both
        // would make the factory push onto the branch it forked from.
        RunUnitSpec unit = unit();

        assertEquals("main", unit.publisher().environment().get("SPIRE_BRANCH_BASE"));
        assertEquals("spire/run_1", unit.publisher().environment().get("SPIRE_BRANCH"));
    }

    @Test
    void theWallClockReachesTheUnit() {
        assertEquals(3600, unit().wallClock().toSeconds());
    }

    @Test
    void aRunWithNoScmCredentialIsRefusedRatherThanBuiltWithout() {
        RunCommand.ExecuteRun noCredential = new RunCommand.ExecuteRun("run::github:acme/app:f:1",
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc", "spire/run_1", "do it", "codex", "gpt-5.6", "img",
                List.of(), 60, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> builder.build(noCredential, new CodexAdapter()));
    }

    @Test
    void anUnknownHarnessFailsBeforeAnythingIsCreated() {
        // A run dispatched to an arm this deployment does not have must fail at the start, not
        // after a container has been created and a model call paid for.
        HarnessRegistry registry = new HarnessRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.forName("opencode"));
        assertThrows(IllegalArgumentException.class, () -> registry.forName("not-a-harness"));
        assertThrows(IllegalArgumentException.class, () -> registry.forName(" "));
        assertEquals(dev.codespire.harness.HarnessType.CODEX, registry.forName("codex").type());
    }

    @Test
    void aCredentialRecordNeverPrintsItsSecret() {
        Credentials.Scm scm = new Credentials.Scm("spire-bot", "ghp-do-not-print", "spire-bot", "ghp-do-not-print");

        assertFalse(scm.toString().contains("ghp-do-not-print"), scm.toString());
        assertTrue(scm.toString().contains("spire-bot"));
    }
    private static final String BUNDLE = "/etc/spire/ca-bundle.crt";

    private static final String CORPORATE_PROXY = "http://TEST-proxy:3128";

    private static final List<String> TRUST_VARIABLES = List.of("SSL_CERT_FILE", "GIT_SSL_CAINFO", "NODE_EXTRA_CA_CERTS");

    private static final List<String> PROXY_VARIABLES = List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy");

    private static final List<String> NO_PROXY_VARIABLES = List.of("NO_PROXY", "no_proxy");

    /** What EnterpriseEnvironmentConfig resolves for a deployment with a corporate CA and proxy: both spellings. */
    private static EnterpriseEnvironment corporateTrustAndProxy() {
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        TRUST_VARIABLES.forEach(name -> environment.put(name, BUNDLE));
        PROXY_VARIABLES.forEach(name -> environment.put(name, CORPORATE_PROXY));
        NO_PROXY_VARIABLES.forEach(name -> environment.put(name, "TEST-intranet.example.test"));
        return new EnterpriseEnvironment(List.of(new HostMount("/opt/acme/ca.crt", BUNDLE)), environment);
    }

    private RunUnitSpec corporateUnit(EgressPolicy.Mode egress) {
        RunUnitBuilder builder = builder(encryption);
        builder.enterprise = corporate(corporateTrustAndProxy());
        builder.egressMode = egress;
        return builder.build(command(), new CodexAdapter());
    }

    /**
     * The corporate TRUST reaches every container of the unit, and its PROXY every container but the agent's.
     *
     * <p>Asserted on the spec rather than on each container, because that placement is the requirement: held at unit
     * level, "every container" is structural and a future arm cannot apply it to two of the three. The init container
     * is the one that matters for trust -- without the bundle its clone fails at the forge, which reads like a bad
     * credential.
     *
     * <p>The agent is the exception for the proxy only. Its one way out is the unit's egress proxy, which chains to the
     * corporate proxy itself; the agent still needs the bundle, since the corporate proxy re-signs what it forwards.
     */
    @Test
    void theCorporateEnvironmentReachesEveryContainerOfTheUnit() {
        RunUnitSpec spec = corporateUnit(EgressPolicy.Mode.REPORT);

        assertEquals(List.of(new HostMount("/opt/acme/ca.crt", BUNDLE)), spec.hostMounts());
        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            for (String name : TRUST_VARIABLES) {
                assertEquals(BUNDLE, spec.environmentFor(container).get(name), name);
            }
        }
        for (ContainerSpec trusted : List.of(spec.init(), spec.publisher())) {
            for (String name : PROXY_VARIABLES) {
                assertEquals(CORPORATE_PROXY, spec.environmentFor(trusted).get(name),
                        name + ": init and the publisher run our own code and keep the deployment's path out");
            }
        }
    }

    /**
     * With egress on, the agent's every proxy variable names the egress proxy, and nothing is exempt from it.
     *
     * <p>Both spellings, because tools disagree on case and a unit where curl is proxied and git is not presents as an
     * intermittent network fault. An inherited {@code NO_PROXY} would let the agent name a host that skips the proxy;
     * there is no route to it anyway, so the call would fail, but it would also go unrecorded.
     */
    @Test
    void withEgressTheAgentsOnlyProxyIsTheEgressProxy() {
        RunUnitSpec spec = corporateUnit(EgressPolicy.Mode.REPORT);
        Map<String, String> agent = spec.environmentFor(spec.agent());

        for (String name : PROXY_VARIABLES) {
            assertEquals(EgressPolicy.PROXY_ADDRESS, agent.get(name), name);
        }
        for (String name : NO_PROXY_VARIABLES) {
            assertEquals("", agent.get(name), name + " must be empty for the agent, not inherited");
        }
    }

    /** No corporate proxy at all: the agent is still pointed at the egress proxy, and init gets no proxy. */
    @Test
    void withEgressAndNoCorporateProxyOnlyTheAgentIsProxied() {
        RunUnitBuilder builder = builder(encryption);
        builder.egressMode = EgressPolicy.Mode.ENFORCE;

        RunUnitSpec spec = builder.build(command(), new CodexAdapter());

        assertEquals(EgressPolicy.PROXY_ADDRESS, spec.environmentFor(spec.agent()).get("HTTPS_PROXY"));
        assertFalse(spec.environmentFor(spec.init()).containsKey("HTTPS_PROXY"));
        assertFalse(spec.environmentFor(spec.publisher()).containsKey("HTTPS_PROXY"));
    }

    /** The unit carries the configured mode and the hosts the HARNESS declares for this payment, never the command's. */
    @Test
    void theUnitsEgressIsTheConfiguredModeWithTheHarnessesHosts() {
        RunUnitBuilder builder = builder(encryption);
        builder.egressMode = EgressPolicy.Mode.ENFORCE;

        assertEquals(new EgressPolicy(EgressPolicy.Mode.ENFORCE, java.util.Set.of("api.openai.com")),
                builder.build(command(), new CodexAdapter()).egress());
        assertEquals(new EgressPolicy(EgressPolicy.Mode.ENFORCE, java.util.Set.of("chatgpt.com")),
                builder.build(command().paidBySignIn(), new CodexAdapter()).egress());
    }

    /** Off is the kill switch: today's unit, with the corporate proxy in the agent too. */
    @Test
    void withEgressOffTheUnitIsTodays() {
        RunUnitSpec spec = corporateUnit(EgressPolicy.Mode.OFF);

        assertEquals(EgressPolicy.OFF, spec.egress());
        for (ContainerSpec container : List.of(spec.init(), spec.agent(), spec.publisher())) {
            assertEquals(CORPORATE_PROXY, spec.environmentFor(container).get("HTTPS_PROXY"));
            assertEquals("TEST-intranet.example.test", spec.environmentFor(container).get("NO_PROXY"));
        }
    }

    /** And it does not disturb what each role was already handed. */
    @Test
    void theCorporateEnvironmentDoesNotDisplaceARolesOwnCredential() throws JsonProcessingException {
        RunUnitBuilder builder = builder(encryption);
        builder.enterprise = corporate(new EnterpriseEnvironment(List.of(),
                java.util.Map.of("HTTPS_PROXY", "http://proxy:3128")));

        RunUnitSpec spec = builder.build(command(), new CodexAdapter());

        assertEquals(SCM_TOKEN, spec.environmentFor(spec.publisher()).get("SPIRE_GIT_SECRET"));
        assertFalse(spec.environmentFor(spec.agent()).containsKey("SPIRE_GIT_SECRET"),
                "the agent still holds no write credential");
    }

    // --- The model proxy (harness reviews step 3) ---

    private static final String PROXY_IMAGE = "TEST-model-proxy:1";

    private static final String PLAINTEXT_FLAG = "SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM";

    private static final String SEAT_ACCESS = "TEST-seat-access-token-0123456789";

    private static final String SEAT_ACCOUNT = "TEST-seat-account-0123456789";

    private static final String SEAT_ID_TOKEN = "TEST-seat-id-token-with-email-claims";

    /** A sign-in file as the orchestrator hands it over: refresh token emptied, the id token still in it. */
    private static String seatFile() {
        com.fasterxml.jackson.databind.node.ObjectNode file = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode tokens = file.putObject("tokens");
        tokens.put("id_token", SEAT_ID_TOKEN);
        tokens.put("access_token", SEAT_ACCESS);
        tokens.put("refresh_token", "");
        tokens.put("account_id", SEAT_ACCOUNT);
        file.put("last_refresh", "1970-01-01T00:00:00Z");
        return file.toString();
    }

    private RunUnitBuilder proxying(CredentialProxyMode mode, EgressPolicy.Mode egress) {
        RunUnitBuilder builder = builder(encryption);
        builder.credentialProxyMode = mode;
        builder.modelProxyImage = PROXY_IMAGE;
        builder.egressMode = egress;
        return builder;
    }

    /** A review's execution as the dispatch sends it: the pull request's target branch as its protected branch. */
    private RunCommand.ExecuteRun reviewExecution(String harnessCredential) {
        return new RunCommand.ExecuteRun(RUN_ID, new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "TEST-source", "review the change", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of(), 3600, packedScm(SCM_LOGIN, SCM_TOKEN),
                encryption.encryptString(harnessCredential, RunCommand.harnessCredentialAad(RUN_ID)), false, "main");
    }

    private static RunCommand.ExecuteReviewRun review(RunCommand.ExecuteRun execution) {
        return new RunCommand.ExecuteReviewRun(RUN_ID, execution,
                new dev.codespire.contract.review.ReviewRunBinding("TEST-review",
                        dev.codespire.contract.review.ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1),
                dev.codespire.contract.review.HarnessReviewInputs.NONE);
    }

    /** Codex, recording every invocation it is handed, so what the arm SAW is asserted, not only what it returned. */
    private static final class RecordingCodex implements dev.codespire.harness.HarnessAdapter {
        private final CodexAdapter codex = new CodexAdapter();
        final List<dev.codespire.harness.HarnessInvocation> seen = new java.util.ArrayList<>();

        private dev.codespire.harness.HarnessInvocation saw(dev.codespire.harness.HarnessInvocation invocation) {
            seen.add(invocation);
            return invocation;
        }

        @Override public dev.codespire.harness.HarnessType type() { return codex.type(); }
        @Override public dev.codespire.harness.HarnessCapabilities capabilities() { return codex.capabilities(); }
        @Override public dev.codespire.harness.PromptDelivery promptDelivery() { return codex.promptDelivery(); }
        @Override public List<String> command(dev.codespire.harness.HarnessInvocation i) { return codex.command(saw(i)); }
        @Override public Map<String, String> environment(dev.codespire.harness.HarnessInvocation i) {
            return codex.environment(saw(i));
        }
        @Override public java.util.Set<String> egressHosts(dev.codespire.harness.HarnessInvocation i) {
            return codex.egressHosts(saw(i));
        }
        @Override public Optional<dev.codespire.harness.CredentialRoute> credentialRoute(
                dev.codespire.harness.HarnessInvocation i) {
            return codex.credentialRoute(saw(i));
        }
        @Override public Optional<dev.codespire.harness.RunEvent> parse(String line) { return codex.parse(line); }
        @Override public dev.codespire.harness.TerminalOutcome classify(int exit, dev.codespire.harness.RunEventSummary s) {
            return codex.classify(exit, s);
        }
        @Override public dev.codespire.harness.UsageReport usage(dev.codespire.harness.RunEventSummary s) {
            return codex.usage(s);
        }
        @Override public Optional<SignInFlow> signIn() { return codex.signIn(); }
    }

    /** No value the agent or the arm could read contains the secret: environment, argv, and every invocation. */
    private static void assertTheAgentNeverHolds(String secret, RunUnitSpec unit, RecordingCodex arm) {
        unit.environmentFor(unit.agent()).forEach((name, value) ->
                assertFalse(value.contains(secret), "the agent's " + name + " holds the credential"));
        unit.agent().argv().forEach(arg -> assertFalse(arg.contains(secret), "the agent's argv holds the credential"));
        assertFalse(arm.seen.isEmpty(), "guards the guard: the arm was asked");
        for (dev.codespire.harness.HarnessInvocation invocation : arm.seen) {
            assertTrue(invocation.credentialProxied(), "every invocation the arm saw was the proxied one");
            invocation.credentials().values().forEach(value ->
                    assertFalse(value.contains(secret), "the arm was handed the credential"));
        }
    }

    @Test
    void aReviewRunIsProxied() {
        RecordingCodex arm = new RecordingCodex();
        RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.REPORT)
                .buildReview(review(reviewExecution(HARNESS_KEY)), arm);

        assertTrue(unit.modelProxy() != null, "a review unit has a model proxy");
        assertEquals(PROXY_IMAGE, unit.modelProxy().image());
        Map<String, String> proxy = unit.environmentFor(unit.modelProxy());
        assertEquals(HARNESS_KEY, proxy.get("SPIRE_MODEL_SECRET"));
        assertEquals("https://api.openai.com", proxy.get("SPIRE_MODEL_UPSTREAM"));
        assertEquals("BEARER", proxy.get("SPIRE_MODEL_INJECTION"));
        assertEquals("/v1/models,/v1/responses", proxy.get("SPIRE_MODEL_PATHS_EXACT"));
        assertFalse(proxy.containsKey(PLAINTEXT_FLAG), "the real credential never goes over plain HTTP");
        assertTrue(unit.modelProxy().mounts().isEmpty());
        assertTheAgentNeverHolds(HARNESS_KEY, unit, arm);
    }

    @Test
    void aSeatReviewRunHandsTheProxyOnlyTheTokenAndAccount() {
        String file = seatFile();
        RecordingCodex arm = new RecordingCodex();
        RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.REPORT)
                .buildReview(review(reviewExecution(file).paidBySignIn()), arm);

        Map<String, String> proxy = unit.environmentFor(unit.modelProxy());
        assertEquals(SEAT_ACCESS, proxy.get("SPIRE_MODEL_SECRET"));
        assertEquals(SEAT_ACCOUNT, proxy.get("SPIRE_MODEL_ACCOUNT"));
        assertEquals("CHATGPT_SEAT", proxy.get("SPIRE_MODEL_INJECTION"));
        assertEquals("https://chatgpt.com", proxy.get("SPIRE_MODEL_UPSTREAM"));
        for (ContainerSpec container : List.of(unit.init(), unit.agent(), unit.publisher(), unit.modelProxy())) {
            unit.environmentFor(container).forEach((name, value) -> {
                assertFalse(value.contains(SEAT_ID_TOKEN), name + " holds the id token and its email claims");
                assertFalse(value.equals(file), name + " holds the whole sign-in file");
            });
        }
        assertFalse(proxy.containsKey(PLAINTEXT_FLAG));
        assertTheAgentNeverHolds(SEAT_ACCESS, unit, arm);
        assertTheAgentNeverHolds(SEAT_ACCOUNT, unit, arm);
    }

    /** REVIEWS means review units: a factory build keeps today's unit, its agent holding the key. */
    @Test
    void aFactoryBuildIsNotProxiedInReviewsMode() {
        RunUnitSpec unit = proxying(CredentialProxyMode.REVIEWS, EgressPolicy.Mode.REPORT)
                .build(command(), new CodexAdapter());

        assertEquals(null, unit.modelProxy());
        assertEquals(HARNESS_KEY, unit.agent().environment().get("OPENAI_API_KEY"));
    }

    @Test
    void isProxiedInAllMode() {
        RecordingCodex arm = new RecordingCodex();
        RunUnitSpec unit = proxying(CredentialProxyMode.ALL, EgressPolicy.Mode.ENFORCE).build(command(), arm);

        assertEquals(HARNESS_KEY, unit.environmentFor(unit.modelProxy()).get("SPIRE_MODEL_SECRET"));
        assertTheAgentNeverHolds(HARNESS_KEY, unit, arm);
    }

    /** The model proxy reaches its vendor only through the egress proxy: with egress off nothing is proxied. */
    @Test
    void withEgressOffNoUnitIsProxied() {
        for (CredentialProxyMode mode : CredentialProxyMode.values()) {
            RunUnitBuilder builder = proxying(mode, EgressPolicy.Mode.OFF);
            RunUnitSpec reviewUnit = builder.buildReview(review(reviewExecution(HARNESS_KEY)), new CodexAdapter());
            RunUnitSpec buildUnit = builder.build(command(), new CodexAdapter());

            assertEquals(null, reviewUnit.modelProxy(), mode.name());
            assertEquals(null, buildUnit.modelProxy(), mode.name());
            assertEquals(HARNESS_KEY, reviewUnit.agent().environment().get("OPENAI_API_KEY"), mode.name());
        }
    }

    /** The harness module cannot see the runtime's constant, so the two spellings of the address are pinned here. */
    @Test
    void theHarnessAndTheRuntimeAgreeOnTheModelProxyAddress() {
        assertEquals(RunUnitSpec.MODEL_PROXY_ADDRESS, dev.codespire.harness.CredentialRoute.AGENT_FACING_ORIGIN);
    }
}