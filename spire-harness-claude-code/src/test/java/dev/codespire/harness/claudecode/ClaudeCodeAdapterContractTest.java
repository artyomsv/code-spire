package dev.codespire.harness.claudecode;

import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessAdapterContract;
import dev.codespire.harness.HarnessInvocation;

/**
 * Claude Code against the rules every arm must obey, on the one invocation shape it accepts: behind the model proxy,
 * without the repository's guidance. Its agent never holds the credential, so the environment rule does not apply.
 */
class ClaudeCodeAdapterContractTest extends HarnessAdapterContract {

    @Override
    protected HarnessAdapter adapter() {
        return new ClaudeCodeAdapter();
    }

    @Override
    protected String sampleModel() {
        return ClaudeCodeInvocations.MODEL;
    }

    @Override
    protected HarnessInvocation runnable(HarnessInvocation invocation) {
        return invocation.proxied().withProjectGuidance(HarnessInvocation.ProjectGuidance.IGNORED);
    }

    @Override
    protected boolean agentHoldsTheCredential() {
        return false;
    }
}
