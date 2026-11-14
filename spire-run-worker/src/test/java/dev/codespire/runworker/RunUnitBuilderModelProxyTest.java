package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
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
class RunUnitBuilderModelProxyTest extends ModelProxyUnitFixture {

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

    private RunCommand.ExecuteRun command() {
        return new RunCommand.ExecuteRun(RUN_ID,
                new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "spire/run_1",
                "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of("deploy/**"), 3600,
                packedScm(SCM_LOGIN, SCM_TOKEN),
                encryption.encryptString(HARNESS_KEY, RunCommand.harnessCredentialAad(RUN_ID)));
    }
}
