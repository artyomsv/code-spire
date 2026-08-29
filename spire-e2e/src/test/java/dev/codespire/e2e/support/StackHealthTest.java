package dev.codespire.e2e.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The precondition every other test in this module assumes.
 *
 * <p>It exists because this module owns no lifecycle: GitLab CE takes around five minutes to boot, so
 * the stack is brought up once and re-run against many times. The cost of that choice is that a stack
 * which is NOT up has to fail here, once, naming the command to fix it — rather than as a scenario
 * timing out forty seconds later with a connection refused nobody reads.
 */
class StackHealthTest {

    @Test
    void theStackIsUp() {
        assertDoesNotThrow(Stack::requireUp);
    }
}
