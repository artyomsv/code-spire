package dev.codespire.worker.pipeline;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/**
 * Writes the rendered prompt to the log when the operator explicitly asks for it.
 *
 * <p>The prompt is assembled in the worker and handed straight to the provider; nothing persists it
 * ({@code review_llm_call} keeps only model and token counts) and Settings → Prompts shows the
 * editable template, not a rendered instance. So when a review says something surprising, the
 * assembly step between "diff + context" and "what the model answered" is the one part of the
 * pipeline an operator cannot see. This is that window.
 *
 * <p><b>Off by default, and that is a security decision, not caution.</b> A rendered prompt contains
 * the full diff and every retrieved context item — material this project otherwise keeps encrypted
 * at rest (Tink, per ADR-014 and DATA-MODEL.md). Turning this on copies it into plaintext logs,
 * which is a wider surface than the stores it came from. It is meant to be switched on for the
 * length of a debugging session and switched back off, not left on.
 *
 * <p>Logged from the worker rather than through LangChain4j's own {@code logRequests(true)}: that
 * flag dumps the raw HTTP request, whose headers carry the provider API key. This logs what was
 * rendered and nothing else, and reads the same across all three provider dialects.
 */
@ApplicationScoped
public class PromptLog {

    private static final Logger LOG = Logger.getLogger(PromptLog.class);

    /**
     * Opt-in per deployment. Named for what it exposes, so nobody enables it expecting a counter.
     * The reviewId rides on every line already via the MDC set by the pipeline.
     */
    @ConfigProperty(name = "spire.review.log-prompts", defaultValue = "false")
    boolean logPrompts;

    /**
     * Wraps a provider so every prompt it is asked to complete is recorded on the way through.
     *
     * <p>A tap rather than a log call beside each {@code complete(...)}: the value of this window is
     * that it shows what the model actually received, and a separate log statement can drift from the
     * argument next to it — a later edit re-renders one and not the other, and the log then lies
     * precisely when someone is trusting it. Wrapping makes them the same object by construction.
     *
     * @param kind which call this provider serves — {@code review}, {@code reconcile}, {@code
     *             follow-up} — so a two-call reconciliation round stays readable in the log.
     */
    public LlmProvider tap(String kind, LlmProvider delegate) {
        return new TappedProvider(kind, delegate, this);
    }

    /**
     * @param kind see {@link #tap}. Overridable so a test can assert what the pipeline offered
     *             without turning logging on.
     */
    public void record(String kind, Prompt prompt) {
        if (!logPrompts || prompt == null) {
            return;
        }
        LOG.infof("Rendered %s prompt%n--- system ---%n%s%n--- user ---%n%s",
                kind, prompt.system(), prompt.user());
    }

    private record TappedProvider(String kind, LlmProvider delegate, PromptLog log) implements LlmProvider {

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
            log.record(kind, prompt);
            return delegate.complete(prompt, params);
        }
    }
}
