package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared fixture of the model proxy builder tests: constants, a proxying builder and a review's execution. */
abstract class ModelProxyUnitFixture {

    static final String RUN_ID = "run::github:acme/app:finding-1:1";

    static final String SCM_LOGIN = "TEST-machine-account";

    static final String SCM_TOKEN = "TEST-scm-token-do-not-print";

    static final String HARNESS_KEY = "TEST-harness-key";

    static final ObjectMapper JSON = new ObjectMapper();

    final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    static final String PROXY_IMAGE = "TEST-model-proxy:1";

    static final String PLAINTEXT_FLAG = "SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM";

    static final String SEAT_ACCESS = "TEST-seat-access-token-0123456789";

    static final String SEAT_ACCOUNT = "TEST-seat-account-0123456789";

    static final String SEAT_ID_TOKEN = "TEST-seat-id-token-with-email-claims";

    /** A sign-in file as the orchestrator hands it over: refresh token emptied, the id token still in it. */
    static String seatFile() {
        com.fasterxml.jackson.databind.node.ObjectNode file = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode tokens = file.putObject("tokens");
        tokens.put("id_token", SEAT_ID_TOKEN);
        tokens.put("access_token", SEAT_ACCESS);
        tokens.put("refresh_token", "");
        tokens.put("account_id", SEAT_ACCOUNT);
        file.put("last_refresh", "1970-01-01T00:00:00Z");
        return file.toString();
    }

    RunUnitBuilder proxying(CredentialProxyMode mode, EgressPolicy.Mode egress) {
        RunUnitBuilder builder = RunUnitBuilderTest.builder(encryption);
        builder.credentialProxyMode = mode;
        builder.modelProxyImage = PROXY_IMAGE;
        builder.egressMode = egress;
        return builder;
    }

    /** A review's execution as the dispatch sends it: the pull request's target branch as its protected branch. */
    RunCommand.ExecuteRun reviewExecution(String harnessCredential) {
        return new RunCommand.ExecuteRun(RUN_ID, new RepoRef("acme", "app"), "https://github.com/acme/app.git",
                "main", "abc1234", "TEST-source", "review the change", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of(), 3600, packedScm(SCM_LOGIN, SCM_TOKEN),
                encryption.encryptString(harnessCredential, RunCommand.harnessCredentialAad(RUN_ID)), false, "main");
    }

    static RunCommand.ExecuteReviewRun review(RunCommand.ExecuteRun execution) {
        return new RunCommand.ExecuteReviewRun(RUN_ID, execution,
                new dev.codespire.contract.review.ReviewRunBinding("TEST-review",
                        dev.codespire.contract.review.ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot", 1),
                dev.codespire.contract.review.HarnessReviewInputs.NONE);
    }

    /** No value the agent or the arm could read contains the secret: environment, argv, and every invocation. */
    static void assertTheAgentNeverHolds(String secret, RunUnitSpec unit, RecordingCodex arm) {
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

    /** What the orchestrator's packer produces: the account's login and token in one envelope. */
    String packedScm(String login, String token) {
        try {
            return encryption.encryptString(JSON.writeValueAsString(new MachineAccountCredential(login, token)),
                    RunCommand.scmCredentialAad(RUN_ID));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
