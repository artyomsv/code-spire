package dev.codespire.worker.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.llm.LlmCredential;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.llm.LlmConfig;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.llm.ModelParamProfile.OutputTokenParam;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Building the LLM client from a brokered credential (ADR-018). LLM config is no
 * longer read from env — the credential's type selects the model. The stub path
 * and the encrypted unpack are exercised by the worker's @QuarkusTest pipeline.
 */
class WorkerLlmProviderTest {

    @Test
    void buildsAnOpenAiClientFromTheCredential() {
        var cred = new LlmCredential("openai", "https://api.openai.com/v1", "sk-test", "gpt-4o", 0.2, 512);
        WorkerLlmProvider.LlmClient client = new WorkerLlmProvider().clientFor(cred, LlmConfig.DEFAULT_TIMEOUT);
        assertNotNull(client.provider());
        assertEquals("gpt-4o", client.params().model());
        assertEquals(512, client.params().maxTokens());
        assertEquals(0.2, client.params().temperature());
    }

    @Test
    void clientForCarriesTheModelParameterProfile() {
        var profile = new ModelParamProfile(OutputTokenParam.MAX_COMPLETION_TOKENS, false, "medium", Map.of());
        var cred = new LlmCredential("openai", "https://api.openai.com/v1", "sk", "o3", 0.2, 256, profile);
        WorkerLlmProvider.LlmClient client = new WorkerLlmProvider().clientFor(cred, LlmConfig.DEFAULT_TIMEOUT);
        assertEquals(OutputTokenParam.MAX_COMPLETION_TOKENS, client.params().profile().outputTokenParam());
        assertFalse(client.params().profile().supportsTemperature());
    }

    @Test
    void credentialProfileSurvivesTheJsonWireRoundTrip() throws Exception {
        // The credential is JSON-serialized, encrypted, brokered, then read back in the
        // worker. If the nested profile record didn't survive, it would normalize back to
        // the legacy MAX_TOKENS dialect and silently resend max_tokens.
        var mapper = new ObjectMapper();
        var profile = new ModelParamProfile(
                OutputTokenParam.MAX_COMPLETION_TOKENS, false, "medium", Map.of("service_tier", "flex"));
        var cred = new LlmCredential("openai", "https://api.openai.com/v1", "sk", "o3", 0.2, 256, profile);

        LlmCredential back = mapper.readValue(mapper.writeValueAsString(cred), LlmCredential.class);
        assertEquals(OutputTokenParam.MAX_COMPLETION_TOKENS, back.profile().outputTokenParam());
        assertFalse(back.profile().supportsTemperature());
        assertEquals("medium", back.profile().reasoningEffort());
        assertEquals("flex", back.profile().extraParams().get("service_tier"));
    }

    @Test
    void rejectsAnUnsupportedProviderType() {
        var cred = new LlmCredential("mystery-llm", "https://x/v1", "k", "m", 0.2, null);
        var thrown = assertThrows(IllegalStateException.class,
                () -> new WorkerLlmProvider().clientFor(cred, LlmConfig.DEFAULT_TIMEOUT));
        assertEquals("Unsupported LLM provider type: mystery-llm", thrown.getMessage());
    }
    /**
     * Both paid paths must hand {@code clientFor} the deployment's configured budget, not the
     * library default.
     *
     * <p>This gap was invisible by construction: {@code LlmConfig.DEFAULT_TIMEOUT} and the shipped
     * {@code spire.llm.timeout-seconds} are the same number, so a call site using the wrong one
     * behaves identically on every deployment that left the default alone. It would surface only on
     * the deployment that raised the timeout — which is the deployment that raised it because it
     * needed to. The fake budget therefore uses a value that matches nothing else.
     */
    @Test
    void bothPaidPathsUseTheConfiguredBudgetAndNotTheLibraryDefault() {
        var captured = new java.util.ArrayList<Duration>();
        WorkerLlmProvider provider = capturing(captured, 42);

        provider.forCommand(new ActionCommand.GenerateReview(
                "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1,
                "TESTSHA", null, 1, null, null, "TEST-CREDENTIAL"));
        provider.forCommand(new ActionCommand.AnswerFollowUp(
                "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1,
                new ThreadRef("TEST-THREAD"), "TEST-COMMENT", "TEST question?", null,
                "TEST-CREDENTIAL", false, 1, 0L, 1.0, null));

        assertEquals(2, captured.size(), "both paths build a client");
        assertEquals(Duration.ofSeconds(42), captured.get(0), "GenerateReview uses the budget");
        assertEquals(Duration.ofSeconds(42), captured.get(1), "AnswerFollowUp uses the budget");
        assertNotEquals(LlmConfig.DEFAULT_TIMEOUT, captured.get(0),
                "a test whose fake budget matched the library default could not tell them apart");
    }

    /** A provider whose credential needs no decryption and whose built client is observable. */
    private static WorkerLlmProvider capturing(java.util.List<Duration> captured, int budgetSeconds) {
        var cred = new LlmCredential("openai", "https://llm.example.invalid", "sk-test", "TEST-MODEL", 0.2, 256);
        WorkerLlmProvider provider = new WorkerLlmProvider() {
            @Override
            LlmCredential unpack(String reviewId, String llmCredential) {
                return cred;
            }

            @Override
            LlmClient clientFor(LlmCredential credential, Duration timeout) {
                captured.add(timeout);
                return super.clientFor(credential, timeout);
            }
        };
        provider.mode = "registry";
        LlmTimeoutBudget budget = new LlmTimeoutBudget();
        budget.timeoutSeconds = budgetSeconds;
        provider.budget = budget;
        return provider;
    }
}
