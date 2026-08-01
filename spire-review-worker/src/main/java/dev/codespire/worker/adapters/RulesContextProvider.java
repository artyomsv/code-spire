package dev.codespire.worker.adapters;

import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Contributes a repository's own review rules — its {@code .codespire} file — as context
 * (EVENT-MODEL S4, {@code ContextContributed{source=RULES}}).
 *
 * <p>The reviewer otherwise sees only the diff and the ticket: what a change does and what it was
 * meant to do, but never how this team writes code. One bot account reviews every repository in a
 * workspace, so a single global prompt has to be vague enough to cover a Java service and a React app
 * at once. Rules that live in the repository are the mechanism that scales with that.
 *
 * <p><b>Credential-free by construction.</b> Every other provider fetches from a third-party system
 * with a credential from the context registry; this one would need the SCM credential, which the
 * aggregator is deliberately never given (ADR-015 brokers least privilege, and the context path
 * cannot be trusted with a token that can also write comments). So the file is read at diff-fetch,
 * where an SCM client already exists, and arrives here as text. Same split as
 * {@code ContextReferenceSource}: the credentialed step happens where credentials already are.
 *
 * <p>The content is untrusted and stays untrusted — the prompt builder fences it like every other
 * context item. That fence does not make the rules safe to take from the PR head, which is why they
 * are read from the target branch; see {@code DiffSource.fetchTextFileOnBranch}.
 */
public class RulesContextProvider implements ContextProvider {

    public static final String SOURCE = "RULES";
    static final String KIND = "RULE";
    /** The path read from the target branch; public because {@code DiffWorker} does the reading. */
    public static final String FILE = ".codespire";

    /** One repo's conventions, not its documentation — a cap keeps rules from crowding out the diff. */
    static final int MAX_CHARS = 8_000;

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request.repoRules() != null && !request.repoRules().isBlank();
    }

    @Override
    public CompletionStage<ContextContribution> contribute(ContextRequest request) {
        if (!supports(request)) {
            // EMPTY, not ERROR: most repositories have no rules file, and that is not a miss.
            return CompletableFuture.completedFuture(
                    new ContextContribution(SOURCE, ContribStatus.EMPTY, List.of(), 0));
        }
        ContextItem item = new ContextItem(KIND, FILE + " — repository review rules",
                clip(request.repoRules().strip()), null);
        return CompletableFuture.completedFuture(
                new ContextContribution(SOURCE, ContribStatus.OK, List.of(item), 0));
    }

    /**
     * Truncates rather than dropping: half a rules file still carries its first and most important
     * conventions, whereas discarding it silently would look identical to a repository having none.
     */
    private static String clip(String rules) {
        return rules.length() <= MAX_CHARS ? rules : rules.substring(0, MAX_CHARS) + "…";
    }
}
