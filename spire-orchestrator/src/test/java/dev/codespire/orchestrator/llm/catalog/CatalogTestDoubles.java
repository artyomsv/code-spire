package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.ModelRate;
import dev.codespire.orchestrator.llm.PricingMode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Hand-built collaborators for the resolver: no database, no CDI. Synthetic TEST- data only. Public so a
 * hand-built pricer, registry or projection elsewhere can be wired with a catalog (CLAUDE.md: a fake
 * with an unwired collaborator opens a real database, or throws, from a plain unit test).
 */
public final class CatalogTestDoubles {

    static final String TEST_CATALOG = "llm-catalog-test/catalog.yaml";

    private CatalogTestDoubles() {
    }

    static String testCatalogText() {
        return BundledCatalog.read(TEST_CATALOG);
    }

    static Supplier<String> testBundle() {
        return CatalogTestDoubles::testCatalogText;
    }

    /** A manual model with rates 5 and 6, so it can never be mistaken for a catalog answer (1 and 2). */
    public static ResolvedModel manual(String name, String type, boolean enabled) {
        return new ResolvedModel(name, type, "TEST manual " + name, ModelSource.MANUAL, PricingMode.METERED,
                Map.of(TokenType.INPUT, ModelRate.rated(5), TokenType.OUTPUT, ModelRate.rated(6)), enabled,
                ModelParamProfile.legacyDefault(), null);
    }

    /** Manual models held in a list; {@code failing} makes every read throw, as a lost database would. */
    public static final class FakeManualModels implements ManualModels {
        final List<ResolvedModel> models = new ArrayList<>();
        boolean failing;

        public FakeManualModels with(ResolvedModel model) {
            models.add(model);
            return this;
        }

        @Override
        public Optional<ResolvedModel> byAnyName(List<String> names) throws SQLException {
            failIfAsked();
            return names.stream().flatMap(name -> models.stream().filter(m -> m.name().equals(name))).findFirst();
        }

        @Override
        public List<ResolvedModel> all() throws SQLException {
            failIfAsked();
            return List.copyOf(models);
        }

        @Override
        public Set<String> allNames() throws SQLException {
            failIfAsked();
            return models.stream().map(ResolvedModel::name).collect(Collectors.toSet());
        }

        private void failIfAsked() throws SQLException {
            if (failing) {
                throw new SQLException("TEST database fault");
            }
        }
    }

    /** The state row in memory, counting reads. */
    public static class FakeStates extends LlmCatalogStateRepository {
        final AtomicInteger reads = new AtomicInteger();
        volatile LlmCatalogState state = LlmCatalogState.EMPTY;
        final List<StoredDocument> stored = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<String> unchanged = new ArrayList<>();

        /** Holds exactly this row. */
        public FakeStates with(LlmCatalogState row) {
            state = row;
            return this;
        }

        FakeStates holding(String document) {
            state = new LlmCatalogState(document, document == null ? null : CatalogSnapshot.sha256Of(document),
                    null, "remote", "https://example.invalid/catalog.yaml", null, null, null, null, null);
            return this;
        }

        @Override
        public LlmCatalogState read() {
            reads.incrementAndGet();
            return state;
        }

        @Override
        public void storeDocument(StoredDocument doc) {
            stored.add(doc);
            state = new LlmCatalogState(doc.document(), doc.sha256(), doc.etag(), doc.source(), doc.url(), null,
                    null, null, null, doc.lastChangeJson());
        }

        /** As the real row does: only an empty row is written, decided on what the row holds now. */
        @Override
        public boolean storeIfEmpty(StoredDocument doc) {
            if (state.document() != null) {
                return false;
            }
            storeDocument(doc);
            return true;
        }

        @Override
        public void recordUnchanged(String etag) {
            unchanged.add(etag);
        }

        @Override
        public void recordFailure(String reason) {
            failures.add(reason);
        }
    }

    static LlmModelCatalog catalog(FakeManualModels manual, FakeStates states, String url) {
        return new LlmModelCatalog(new LlmModelCatalog.Wiring(manual, states, testBundle(), url));
    }

    /** The TEST- catalog read from this URL's stored row, falling back to the bundled TEST- copy. */
    public static LlmModelCatalog catalogAt(String url, FakeStates states) {
        return catalog(new FakeManualModels(), states, url);
    }

    /** The TEST- catalog alone (url none), with these manual models. */
    public static LlmModelCatalog testCatalog(FakeManualModels manual) {
        return catalog(manual, new FakeStates(), "none");
    }

    /** The TEST- catalog with no manual models. */
    public static LlmModelCatalog testCatalog() {
        return testCatalog(new FakeManualModels());
    }

    /** The TEST- catalog over manual tables that cannot be read: every manual read throws. */
    public static LlmModelCatalog failingCatalog() {
        FakeManualModels failing = new FakeManualModels();
        failing.failing = true;
        return testCatalog(failing);
    }
}
