package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import dev.codespire.orchestrator.llm.catalog.ResolvedModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Warnings about a review model chosen beside its provider (docs/superpowers/specs/2026-11-20-llm-provider-without-model-design.md,
 * sections 3 and 5). Neither refuses a review: a model of another vendor than its provider runs, as a gateway setup
 * always has, and a switched-off or retired model keeps running for API reviews. Both ask the operator to choose again.
 *
 * <p>Each row names the model and where it is chosen: the default review model (no subject) or a repository (the
 * repository is the subject).
 */
@ApplicationScoped
public class LlmChoiceAttentionRows {

    static final String NOT_FOR_PROVIDER = "LLM_MODEL_NOT_FOR_PROVIDER";
    static final String SWITCHED_OFF = "LLM_MODEL_SWITCHED_OFF";

    @Inject
    LlmModelCatalog catalog;

    /** The model the default provider spends, checked against that provider's type. */
    void checkDefault(String providerType, String model, List<AttentionView> rows) {
        check(new Choice(null, "the default review model", providerType, model), rows);
    }

    /** A repository's own model, checked against its own provider's type. */
    void checkRepository(String repository, String providerType, String model, List<AttentionView> rows) {
        check(new Choice(repository, "repository " + repository, providerType, model), rows);
    }

    private record Choice(String subject, String where, String providerType, String model) {
    }

    /** An unknown model adds nothing here: it cannot be priced, and the pricing row already blocks it. */
    private void check(Choice choice, List<AttentionView> rows) {
        Optional<ResolvedModel> resolved = catalog.resolve(choice.model());
        if (resolved.isEmpty()) {
            return;
        }
        ResolvedModel model = resolved.orElseThrow();
        if (!model.type().equals(choice.providerType())) {
            rows.add(new AttentionView(NOT_FOR_PROVIDER, Severity.WARNING, choice.subject(),
                    "Model " + choice.model() + ", " + choice.where() + ", is a " + model.type() + " model, but its"
                            + " provider is " + choice.providerType() + ". Reviews still run, as through a gateway."
                            + " Choose a " + choice.providerType() + " model to match.", link(choice)));
        }
        if (!model.enabled()) {
            rows.add(new AttentionView(SWITCHED_OFF, Severity.WARNING, choice.subject(),
                    "Model " + choice.model() + ", " + choice.where() + ", is switched off or retired. Reviews still"
                            + " run on it; choose another model.", link(choice)));
        }
    }

    private static String link(Choice choice) {
        return choice.subject() == null ? "/settings/llm" : "/settings/repositories";
    }
}
