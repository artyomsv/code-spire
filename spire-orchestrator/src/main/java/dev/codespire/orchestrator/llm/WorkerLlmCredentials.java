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

    /** The encrypted default LLM credential bound to a workspace, or empty when no default is set. */
    public Optional<String> packDefault(String workspace) {
        return registry.resolveDefault().map(cfg -> pack(cfg, workspace));
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
