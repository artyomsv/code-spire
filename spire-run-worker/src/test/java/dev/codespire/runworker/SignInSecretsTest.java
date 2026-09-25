package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool that echoes one token prints that token, not the whole file, so a sign-in is scrubbed token by
 * token as well as whole (M3.5 part F). Placeholder values: this is about which strings are listed.
 */
class SignInSecretsTest {

    private static final String FILE = "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"TEST-access-token-0123456789\","
            + "\"id_token\":\"TEST-id-token-0123456789\",\"refresh_token\":\"\",\"account_id\":\"TEST-account-0123456789\"}}";

    @Test
    void everyTokenInTheFileIsScrubbedAsWellAsTheWholeFile() {
        var secrets = Credentials.signInSecrets(FILE);

        assertTrue(secrets.contains(FILE));
        assertTrue(secrets.contains("TEST-access-token-0123456789"));
        assertTrue(secrets.contains("TEST-id-token-0123456789"));
        assertTrue(secrets.contains("TEST-account-0123456789"));
    }

    /** A short word is not a secret, and scrubbing it would mangle every log line that says it. */
    @Test
    void shortWordsAreLeftAlone() {
        var secrets = Credentials.signInSecrets(FILE);

        assertFalse(secrets.contains("chatgpt"));
        assertFalse(secrets.contains(""));
    }

    @Test
    void anUnreadableFileIsStillScrubbedWhole() {
        assertTrue(Credentials.signInSecrets("not json").contains("not json"));
    }
}
