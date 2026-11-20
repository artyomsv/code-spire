package dev.codespire.orchestrator.llm;

import dev.codespire.orchestrator.settings.AppSettingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * The global default review model: the model a review spends when its repository names no provider, beside the
 * default provider (docs/superpowers/specs/2026-11-20-llm-provider-without-model-design.md, section 4). Stored as the
 * app setting {@value #KEY}.
 */
@ApplicationScoped
public class LlmDefaultModel {

    public static final String KEY = "llm.default-model";

    @Inject
    AppSettingRepository settings;

    /**
     * The default review model, or empty when none is chosen.
     *
     * @throws AppSettingRepository.SettingReadFailed on a database fault, so the review is redelivered rather than
     *         refused {@code NO_DEFAULT_MODEL} for a hiccup
     */
    public Optional<String> current() {
        return settings.require(KEY);
    }
}
