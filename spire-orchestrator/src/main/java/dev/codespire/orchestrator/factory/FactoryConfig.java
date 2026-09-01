package dev.codespire.orchestrator.factory;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Map;

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
}
