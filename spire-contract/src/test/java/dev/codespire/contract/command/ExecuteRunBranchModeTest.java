package dev.codespire.contract.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a run says where it is allowed to push (ADR-040).
 *
 * <p>The publisher decides on {@code SPIRE_BRANCH_MODE} and {@code SPIRE_PROTECTED_BRANCH}, and both
 * reach it from this command. Two components rather than one boolean, because the protected branch is
 * a NAME the orchestrator read from the pull request — the publisher must not be able to make an API
 * call to find it, and a deployment whose trunk is {@code develop} is not covered by any convention
 * list the publisher could hold.
 */
class ExecuteRunBranchModeTest {

    private static RunCommand.ExecuteRun run() {
        return new RunCommand.ExecuteRun("run::github:acme/app:subject:1", new RepoRef("acme", "app"),
                "https://github.com/acme/app.git", "main", "cafe1234", "spire/fix", "do the thing",
                "codex", "gpt-x", "img", List.of(), 900,
                "TEST-scm-token-do-not-print", "TEST-harness-key-do-not-print");
    }

    /**
     * The default is the M0 behaviour, and it is asserted rather than assumed.
     *
     * <p>A command built by any existing call site must not silently acquire the permissive mode:
     * every run dispatched before ADR-040 pushes into the factory's own namespace, and a default of
     * {@code existing} would lift that floor for all of them at once.
     */
    @Test
    void aCommandThatSaysNothingUsesTheNamespaceMode() {
        assertFalse(run().pushesToAnExistingBranch());
        assertEquals("", run().protectedBranch());
    }

    @Test
    void aFixRunSaysItPushesToAnExistingBranchAndWhichBranchIsOffLimits() {
        RunCommand.ExecuteRun fix = run().onExistingBranch("develop");

        assertTrue(fix.pushesToAnExistingBranch());
        assertEquals("develop", fix.protectedBranch());
    }

    /**
     * <b>The wither exists because the convenience constructor would otherwise drop these silently.</b>
     *
     * <p>Adding a component to a wire record keeps every shorter constructor valid, so every rebuild
     * site still compiles while quietly losing the new value — the trap this repository records and
     * has paid for. So the wither enumerates every component once, here, and this asserts it carries
     * them all rather than only the two it sets.
     */
    @Test
    void theWitherCarriesEveryOtherComponentThrough() {
        RunCommand.ExecuteRun original = run();
        RunCommand.ExecuteRun fix = original.onExistingBranch("develop");

        assertEquals(original.runId(), fix.runId());
        assertEquals(original.repo(), fix.repo());
        assertEquals(original.remoteUri(), fix.remoteUri());
        assertEquals(original.baseBranch(), fix.baseBranch());
        assertEquals(original.baseCommit(), fix.baseCommit());
        assertEquals(original.branch(), fix.branch());
        assertEquals(original.prompt(), fix.prompt());
        assertEquals(original.harness(), fix.harness());
        assertEquals(original.model(), fix.model());
        assertEquals(original.agentImage(), fix.agentImage());
        assertEquals(original.protectedPaths(), fix.protectedPaths());
        assertEquals(original.maxWallClockSeconds(), fix.maxWallClockSeconds());
        assertEquals(original.scmCredential(), fix.scmCredential());
        assertEquals(original.harnessCredential(), fix.harnessCredential());
    }

    /**
     * A mode with no protected branch is refused HERE, not left for the publisher to catch.
     *
     * <p>The publisher does refuse it, and that refusal is the floor. But it fires inside a container
     * after an image pull and a clone, and reports as a misconfigured publisher rather than as a
     * command that should never have been sent. Refusing at construction makes it a caller bug at the
     * point the caller exists.
     */
    @Test
    void existingModeWithoutAProtectedBranchIsRefused() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> run().onExistingBranch(blank),
                    "value=" + blank);
        }
    }

    /**
     * A fix pushes to a pull request's SOURCE branch, so naming its destination is a caller bug.
     *
     * <p>The publisher refuses exactly this, and that refusal is the floor — but it fires inside a
     * container after an image pull and a clone. The compact constructor already refuses a blank
     * destination on that argument; refusing this one is the same argument applied to the other
     * half of what the publisher checks.
     */
    @Test
    void aRunMayNotPushToTheBranchItNamesAsOffLimits() {
        assertThrows(IllegalArgumentException.class, () -> run().onExistingBranch("spire/fix"));
    }

    /**
     * <b>A command serialised before ADR-040 still reads as namespace mode.</b>
     *
     * <p>The convenience constructor's comment claims exactly this, and nothing asserted it. The
     * claim is about JSON, not about Java: under ADR-014 the bus keeps short retention, so an
     * in-flight command written by the previous version is deserialised by the new one during any
     * rolling upgrade. If {@code existingBranch} defaulted to true, or {@code protectedBranch}
     * arrived null and reached a caller that reads it, that upgrade window is where it would show.
     *
     * <p>The JSON is written out by hand rather than round-tripped, because a round trip asserts the
     * new version agrees with itself — which it always does. Only an OLD payload can fail this.
     */
    @Test
    void aCommandSerialisedBeforeAdr040ReadsAsNamespaceMode() throws Exception {
        String legacyJson = """
                {"type":"ExecuteRun","runId":"run::github:acme/app:subject:1",
                 "repo":{"workspace":"acme","slug":"app"},
                 "remoteUri":"https://github.com/acme/app.git","baseBranch":"main",
                 "baseCommit":"cafe1234","branch":"spire/fix","prompt":"do the thing",
                 "harness":"codex","model":"gpt-x","agentImage":"img","protectedPaths":[],
                 "maxWallClockSeconds":900,"scmCredential":"TEST-scm-token-do-not-print",
                 "harnessCredential":"TEST-harness-key-do-not-print"}
                """;

        RunCommand.ExecuteRun revived = (RunCommand.ExecuteRun)
                new ObjectMapper().readValue(legacyJson, RunCommand.class);

        assertFalse(revived.pushesToAnExistingBranch(), "an old run must not acquire the new mode");
        // Empty, NOT null: the compact constructor normalises it, and callers read it with isBlank.
        assertEquals("", revived.protectedBranch());
        assertEquals("spire/fix", revived.branch(), "and nothing else shifted");
    }

    /** The credentials stay redacted, and the new components are not secret so they are shown. */
    @Test
    void theStringFormShowsTheModeAndStillHidesTheCredentials() {
        String shown = run().onExistingBranch("develop").toString();

        assertTrue(shown.contains("existingBranch=true"), shown);
        assertTrue(shown.contains("protectedBranch=develop"), shown);
        // Assert the VALUE is absent, not a string that could never appear. The previous check was
        // `contains("scm\"")`, and toString emits no quote character anywhere — so it could not
        // fail, and the redaction was carried entirely by the line below it.
        assertFalse(shown.contains("TEST-scm-token-do-not-print"), shown);
        assertFalse(shown.contains("TEST-harness-key-do-not-print"), shown);
        assertTrue(shown.contains("scmCredential=***"), shown);
    }
}
