package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pull ref a fork review carries and the pull ref init accepts are one pattern (harness reviews step 8).
 *
 * <p>The run worker validates the ref with {@code PullRefs.PATTERN} (spire-contract) and init's clone validates it with
 * {@code WorkspaceClone.FETCH_REF_PATTERN} (spire-workspace). The worker may not depend on spire-workspace
 * ({@code RunWorkerRunsNoGitTest}) and spire-workspace takes nothing from spire-contract, so no test in either module
 * sees both. A ref the worker accepts and init refuses fails a fork review after the model proxy is up; the other way
 * round, init would fetch a ref the command's own validation never allowed. Read as text, like every guard in this
 * module.
 */
class ForkRefPatternsAgreeTest {

    private static final String CONTRACT_SIDE = "spire-contract/src/main/java/dev/codespire/contract/review/PullRefs.java";

    private static final String INIT_SIDE = "spire-workspace/src/main/java/dev/codespire/workspace/WorkspaceClone.java";

    private static String literalOf(String file, String constant) throws IOException {
        Path root = Path.of(System.getProperty("spire.repoRoot"));
        String source = Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        Matcher declaration = Pattern
                .compile("static\\s+final\\s+String\\s+" + constant + "\\s*=\\s*\"([^\"]+)\";")
                .matcher(source);
        assertTrue(declaration.find(), "no `static final String " + constant + " = \"...\"` in " + file);
        return declaration.group(1);
    }

    @Test
    void bothRefPatternsAgree() throws IOException {
        String contract = literalOf(CONTRACT_SIDE, "PATTERN");
        String init = literalOf(INIT_SIDE, "FETCH_REF_PATTERN");

        assertEquals(contract, init, "the worker and init accept different pull refs");
        assertEquals("refs/(pull|merge-requests)/[1-9][0-9]{0,17}/head", contract, "guards the guard: a real pattern was read");
    }
}
