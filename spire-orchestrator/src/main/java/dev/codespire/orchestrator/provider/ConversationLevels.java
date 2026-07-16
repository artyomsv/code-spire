package dev.codespire.orchestrator.provider;

import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Resolves the effective conversation level: a provider's value overrides the global default (spec §2). */
@ApplicationScoped
public class ConversationLevels {

    static final String GLOBAL_KEY = "conversation.level";

    @Inject
    ProviderRegistry providers;

    @Inject
    AppSettingRepository settings;

    /** The global default (app_setting 'conversation.level'); absent → REPORT_ONLY (fail-safe: no conversation). */
    public ConversationLevel globalDefault() {
        return ConversationLevel.parse(settings.get(GLOBAL_KEY).orElse(null));
    }

    public void setGlobalDefault(ConversationLevel level) {
        settings.set(GLOBAL_KEY, level.name());
    }

    /** The provider's override if set, else the global default. */
    public ConversationLevel effectiveLevel(String type, String workspace) {
        ConversationLevel globalDefault = globalDefault();
        return providers.resolve(type, workspace)
                .map(p -> effective(p.conversationLevel(), globalDefault))
                .orElse(globalDefault);
    }

    /** Pure decision: a non-blank provider value wins; otherwise the global default. */
    static ConversationLevel effective(String providerRaw, ConversationLevel globalDefault) {
        return (providerRaw == null || providerRaw.isBlank())
                ? globalDefault
                : ConversationLevel.parse(providerRaw);
    }
}
