package dev.codespire.harness.codex;

import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessAdapterContract;

/**
 * Codex against the rules every arm must obey. The contract lives in spire-harness so a second arm
 * inherits it rather than restating it — and so an arm that quietly puts the prompt back in argv
 * fails a test instead of passing review.
 */
class CodexAdapterContractTest extends HarnessAdapterContract {

    @Override
    protected HarnessAdapter adapter() {
        return new CodexAdapter();
    }

    @Override
    protected String sampleModel() {
        return "gpt-5.6";
    }
}
