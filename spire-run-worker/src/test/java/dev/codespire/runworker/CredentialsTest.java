package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.HarnessInvocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialsTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    private static final String TOKEN = "TEST-token-do-not-print";

    private final EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());

    private final Credentials credentials = credentials(encryption);

    private static Credentials credentials(EncryptionService encryption) {
        Credentials c = new Credentials();
        c.encryption = encryption;
        c.mapper = new ObjectMapper();
        return c;
    }

    private String packed(String runId) throws Exception {
        return encryption.encryptString(
                new ObjectMapper().writeValueAsString(new MachineAccountCredential("TEST-login", TOKEN)),
                RunCommand.scmCredentialAad(runId));
    }

    @Test
    void theEnvelopeCarriesTheLoginAndTheTokenForBothDirections() throws Exception {
        Credentials.Scm scm = credentials.scm(RUN_ID, packed(RUN_ID));

        assertEquals("TEST-login", scm.readUsername());
        assertEquals("TEST-login", scm.writeUsername());
        assertEquals(TOKEN, scm.readSecret());
        assertEquals(TOKEN, scm.writeSecret());
    }

    @Test
    void aCiphertextPackedForAnotherRunDoesNotOpen() throws Exception {
        // The AAD binds the envelope to its run: a value lifted from one record cannot be presented
        // under another run id, which is what makes a dead-lettered copy useless to a replayer.
        String other = packed("run::github:TEST-acme/app:other:1");
        assertThrows(RuntimeException.class, () -> credentials.scm(RUN_ID, other));
    }

    @Test
    void aRawTokenIsRefusedNotUsed() {
        // The round-1 shape: the token itself on the command. It must not decrypt to anything.
        assertThrows(RuntimeException.class, () -> credentials.scm(RUN_ID, TOKEN));
        assertThrows(IllegalArgumentException.class, () -> credentials.scm(RUN_ID, " "));
    }

    @Test
    void theRecordNeverPrintsASecret() throws Exception {
        String printed = credentials.scm(RUN_ID, packed(RUN_ID)).toString();
        assertFalse(printed.contains(TOKEN), printed);
        assertTrue(printed.contains("TEST-login"), printed);
    }

    @Test
    void theHarnessKeyArrivesUnderTheNeutralNameOrNotAtAll() {
        String packed = encryption.encryptString("TEST-model-key", RunCommand.harnessCredentialAad(RUN_ID));

        assertEquals(Map.of(HarnessInvocation.CREDENTIAL, "TEST-model-key"), credentials.harnessEnv(RUN_ID, packed));
        assertEquals(Map.of(), credentials.harnessEnv(RUN_ID, null), "no credential packed means none, not an empty string");
    }
}
