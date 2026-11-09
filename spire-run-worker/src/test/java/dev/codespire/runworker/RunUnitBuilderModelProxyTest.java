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
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model proxy (harness reviews step 3) as the builder places it: which units get one, what it is handed, and that
 * neither the agent nor the arm ever holds the real credential.
 */
class RunUnitBuilderModelProxyTest {

    private static final String RUN_ID = "run::github:acme/app:finding-1:1";

    private static final String SCM_LOGIN = "TEST-machine-account";

    private static final String SCM_TOKEN = "TEST-scm-token-do-not-print";

    private static final String HARNESS_KEY = "TEST-harness-key";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

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
        RunUnitBuilder builder = RunUnitBuilderTest.builder(encryption);
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

    private RunCommand.ExecuteRun command() {
        return new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1",
                "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of("deploy/**"), 3600,
                packedScm(SCM_LOGIN, SCM_TOKEN),
                encryption.encryptString(HARNESS_KEY, RunCommand.harnessCredentialAad(RUN_ID)));
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
}
