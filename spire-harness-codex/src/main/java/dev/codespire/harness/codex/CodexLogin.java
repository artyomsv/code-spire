package dev.codespire.harness.codex;

import dev.codespire.harness.HarnessInvocation;

/** How a Codex run signs in: the step that runs before {@code codex exec}, and the names it reads. */
final class CodexLogin {

    /** What the Codex process reads its key from. Vendor knowledge, and this arm's alone. */
    static final String API_KEY_VARIABLE = "OPENAI_API_KEY";

    /**
     * Where the sign-in file waits for the login step to write it (M3.5 part F). This arm's own name: the
     * script below reads it once, writes the file Codex reads, and unsets it before Codex starts.
     */
    static final String SIGN_IN_VARIABLE = "CODEX_SIGN_IN_FILE";

    /**
     * The exit code the login step uses when it cannot authenticate, so a run that never reached
     * the model is not reported as one the model failed to answer.
     *
     * <p>Chosen outside sysexits (64-78), outside the shell's reserved codes (126-165), and outside
     * what codex itself returns (1 for an error, 101 for a panic), so it cannot be confused with a
     * code the harness produced after login succeeded.
     */
    static final int LOGIN_FAILED = 120;

    private CodexLogin() {
    }

    /**
     * How this run signs Codex in: a key piped into {@code --with-api-key}, or a sign-in file written
     * where Codex reads one.
     *
     * <p>The file is written because no login flag takes it. {@code --with-access-token} was the plan and
     * refuses a ChatGPT access token (measured 2026-09-25, codex-cli 0.156.1: design §5.3); the file,
     * with its refresh token emptied by the orchestrator, is accepted and runs. Written owner-only
     * ({@code umask 077}), and the variable that carried it is unset before Codex starts, so the process
     * that runs ticket text does not also inherit it. The agent can still read the file, as it can read
     * a key today — what it cannot hold is a refresh token, because none was ever sent.
     */
    static String script(HarnessInvocation invocation) {
        if (isSeat(invocation)) {
            // A proxied seat writes PlaceholderSignIn's file through the same step: environment() puts it there.
            return "mkdir -p \"$HOME/.codex\" && (umask 077 && printenv " + SIGN_IN_VARIABLE
                    + " > \"$HOME/.codex/auth.json\") || exit " + LOGIN_FAILED + "; "
                    + "unset " + SIGN_IN_VARIABLE + "; ";
        }
        if (invocation.credentialProxied()) {
            // The provider below reads its placeholder key from the environment; no auth.json is needed (spike).
            return "";
        }
        return "printenv " + API_KEY_VARIABLE + " | codex login --with-api-key >/dev/null"
                + " || exit " + LOGIN_FAILED + "; ";
    }

    static boolean isSeat(HarnessInvocation invocation) {
        return invocation.credentials().containsKey(HarnessInvocation.SIGN_IN);
    }
}
