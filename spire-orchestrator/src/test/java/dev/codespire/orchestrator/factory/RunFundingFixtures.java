package dev.codespire.orchestrator.factory;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.LlmModelRegistry;

import java.util.List;

/**
 * The fakes a unit test needs to reach {@link RunFunding#fund} without a database: one method per collaborator, each
 * overriding exactly the method {@code fund} reaches (an un-overridden method would open a real database), each noting
 * in {@code reached} that it was asked. The credential pool differs per test, so the caller supplies it.
 */
final class RunFundingFixtures {

    private RunFundingFixtures() {
    }

    static RunFunding funding(List<String> reached, HarnessCredentialPool pool) {
        RunFunding funding = new RunFunding();
        funding.models = models(reached);
        funding.catalogues = catalogues(reached);
        funding.pricer = pricer(reached);
        funding.spend = spend(reached);
        funding.pool = pool;
        return funding;
    }

    static LlmModelRegistry models(List<String> reached) {
        return new LlmModelRegistry() {
            @Override
            public boolean isDisabled(String model) {
                reached.add("models");
                return false;
            }
        };
    }

    static HarnessCatalogues catalogues(List<String> reached) {
        return new HarnessCatalogues() {
            @Override
            public Admission admit(String harness, String model, String effort) {
                reached.add("admit");
                return new Admission(null, "TEST-image");
            }
        };
    }

    static LlmModelPricer pricer(List<String> reached) {
        return new LlmModelPricer() {
            @Override
            public List<TokenType> unpricedTypes(String model, String harness) {
                reached.add("pricer");
                return List.of();
            }
        };
    }

    static SpendGate spend(List<String> reached) {
        return new SpendGate() {
            @Override
            public Decision decide() {
                reached.add("spend");
                return Decision.of(CapRefusal.allow());
            }
        };
    }
}
