package dev.codespire.orchestrator.factory;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Map;
import java.util.Optional;

/**
 * What a dispatched run inherits from the deployment rather than from the request.
 *
 * <p>The agent image is keyed by harness NAME so this module never spells one. Which arms exist is
 * the worker's composition root's business ({@code HarnessRegistry}), and the neutrality scan holds
 * the orchestrator to that — a literal image name here was the first leak it found. A harness with
 * no image configured is refused at dispatch: before a row is written, before a token leaves this
 * service.
 */
@ConfigMapping(prefix = "spire.factory")
public interface FactoryConfig {

    /** Harness name → agent image, e.g. {@code spire.factory.agent-image.codex=spire-agent-codex:latest}. */
    @WithName("agent-image")
    Map<String, String> agentImage();

    /** The wall-clock cap handed to the runtime; the run is cancelled and salvaged when it elapses. */
    @WithName("wall-clock-seconds")
    @WithDefault("1800")
    long wallClockSeconds();

    /**
     * What a {@code /fix} run uses, since nobody types it.
     *
     * <p>The REST endpoint takes the harness and the model from its request body. {@code /fix} has
     * no request: FR-F27's premise is that the finding is the whole specification, and letting a
     * commenter choose the model would let them choose the price. So these come from the
     * deployment.
     *
     * <p><b>Optional, with no defaults, and the emptiness is the opt-in.</b> The house rule is no
     * defaults for environment-specific values and a fail-fast when unset — but a mapping that
     * REFUSED TO START would break every deployment that never uses {@code /fix}, which is all of
     * them today. So the refusal moves to the command: an operator who has not named a harness and
     * a model has not turned the feature on, and the author is told exactly which key is missing.
     * That is the shape the spend cap already uses, where unset is a deliberate decision rather
     * than a crash.
     */
    Fix fix();

    interface Fix {

        /** Must be a key of {@link #agentImage()}, or the dispatch refuses before it spends. */
        Optional<String> harness();

        /** Must be priceable, or the charge ledger records a run whose cost is unknowable. */
        Optional<String> model();
    }
}
