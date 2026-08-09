package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.LlmCredential;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Resolves the global-default LLM provider and packs its config, encrypted, onto a
 * GenerateReview command (ADR-018) — the LLM analog of
 * {@link dev.codespire.orchestrator.provider.WorkerCredentials} for SCM.
 */
@ApplicationScoped
public class WorkerLlmCredentials {

    private static final Logger LOG = Logger.getLogger(WorkerLlmCredentials.class);

    @Inject
    LlmProviderRegistry registry;

    @Inject
    LlmModelRegistry models;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /**
     * The encrypted default LLM credential bound to a workspace, or the reason it must not be spent.
     *
     * <p>The priceability check lives HERE rather than at each emit site, because pricing happens only
     * when the result comes back: this is the last point at which an unpriceable call can be prevented
     * rather than merely reported, and a caller cannot take the credential without being told. The
     * review path checked; the conversation path did not, so an upgraded deployment refused new reviews
     * while an author replying in a live thread still made the bot spend.
     *
     * <p>An unpriceable model is refused BEFORE packing, so no credential is encrypted for a call that
     * will not be made.
     */
    public DefaultLlm resolveDefault(String workspace) {
        Optional<LlmProviderConfig> cfg = registry.resolveDefault();
        if (cfg.isEmpty()) {
            return DefaultLlm.noDefaultProvider();
        }
        String model = cfg.get().model();
        if (!models.isPriceable(model)) {
            return DefaultLlm.notPriceable(model);
        }
        return DefaultLlm.spendable(pack(cfg.get(), workspace), model);
    }

    private String pack(LlmProviderConfig cfg, String workspace) {
        // The parameter dialect lives on the model catalog, keyed by model name;
        // fall back to the classic Chat Completions dialect for uncatalogued models.
        Optional<ModelParamProfile> resolved = models.profileForName(cfg.model());
        ModelParamProfile profile = resolved.orElseGet(ModelParamProfile::legacyDefault);
        if (resolved.isEmpty()) {
            // Silent fallback here is exactly what makes "still sends max_tokens" baffling —
            // say so, and point at the fix.
            LOG.warnf("LLM model '%s' is not in the catalog — using the default max_tokens dialect. "
                    + "If this is a reasoning model (o1/o3/gpt-5), register it in Settings → LLM → Models "
                    + "with output token limit = max_completion_tokens.", cfg.model());
        } else {
            LOG.infof("LLM model '%s' resolved: outputTokenParam=%s, supportsTemperature=%s, reasoningEffort=%s",
                    cfg.model(), profile.outputTokenParam(), profile.supportsTemperature(), profile.reasoningEffort());
        }
        LlmCredential cred = new LlmCredential(cfg.type(), cfg.baseUrl(), cfg.apiKey(),
                cfg.model(), cfg.temperature(), cfg.maxTokens(), profile);
        try {
            return encryption.encryptString(mapper.writeValueAsString(cred), LlmCredential.aad(workspace));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to pack LLM credential for " + workspace, e);
        }
    }
}
