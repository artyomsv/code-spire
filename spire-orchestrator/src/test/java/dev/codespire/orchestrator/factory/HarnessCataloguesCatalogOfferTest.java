package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.HarnessImageResult;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claude Code's offer comes from the model catalog (manual models first): the enabled models of the vendor
 * whose key pays it. Codex keeps reading its image label. Built by hand over the real harness_catalogue
 * table and the TEST- catalog, so the configured images are this test's own.
 */
@QuarkusTest
class HarnessCataloguesCatalogOfferTest {

    private static final String CLAUDE = "claude_code";
    private static final String CODEX = "codex";
    private static final String CLAUDE_IMAGE = "TEST-claude-code-image";
    private static final String CODEX_IMAGE = "TEST-codex-image";

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Inject
    LlmModelPricer pricer;

    @AfterEach
    void removeTheRows() throws SQLException {
        execute("DELETE FROM harness_catalogue WHERE image IN (?, ?)", CLAUDE_IMAGE, CODEX_IMAGE);
    }

    private HarnessCatalogues catalogues(String claudeImage, CatalogTestDoubles.FakeManualModels manual) {
        HarnessCatalogues catalogues = new HarnessCatalogues();
        catalogues.dataSource = dataSource;
        catalogues.mapper = mapper;
        catalogues.models = CatalogTestDoubles.testCatalog(manual);
        catalogues.pricer = pricer;
        catalogues.config = new FactoryConfig() {
            @Override public Map<String, String> agentImage() {
                return Map.of(CODEX, CODEX_IMAGE, CLAUDE, claudeImage);
            }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 900; }
            @Override public Fix fix() { throw new AssertionError("not read here"); }
        };
        return catalogues;
    }

    private HarnessCatalogues configured() {
        return catalogues(CLAUDE_IMAGE, new CatalogTestDoubles.FakeManualModels());
    }

    private static List<String> slugs(HarnessCatalogues.Catalogue catalogue) {
        return catalogue.offered().stream().map(HarnessImageResult.Model::slug).toList();
    }

    @Test
    void claudeCodeOffersTheEnabledAnthropicModels() {
        HarnessCatalogues.Catalogue catalogue = configured().get(CLAUDE).orElseThrow();

        assertEquals(HarnessImageResult.Status.OK, catalogue.status());
        assertEquals(List.of("TEST-cat-a"), slugs(catalogue));
        assertEquals(List.of(), catalogue.offered().getFirst().efforts());
        assertEquals(CLAUDE_IMAGE, catalogue.image());
        assertNull(catalogue.pinnedImage());
    }

    @Test
    void claudeCodeRefusesANonAnthropicModel() {
        assertEquals("model_not_run_by_harness", configured().admit(CLAUDE, "TEST-cat-o", null).refusal());
        assertEquals("model_not_run_by_harness", configured().admit(CLAUDE, "TEST-cat-retired", null).refusal());
        assertNull(configured().admit(CLAUDE, "TEST-cat-a", null).refusal());
    }

    @Test
    void claudeCodeOffersAnEnabledManualAnthropicModel() {
        CatalogTestDoubles.FakeManualModels manual = new CatalogTestDoubles.FakeManualModels()
                .with(CatalogTestDoubles.manual("TEST-manual-anthropic", "anthropic", true))
                .with(CatalogTestDoubles.manual("TEST-manual-off", "anthropic", false));

        HarnessCatalogues.Catalogue catalogue = catalogues(CLAUDE_IMAGE, manual).get(CLAUDE).orElseThrow();

        assertEquals(List.of("TEST-cat-a", "TEST-manual-anthropic"), slugs(catalogue));
    }

    @Test
    void claudeCodeWithAnUnlabelledImageIsNotNoCatalogue() throws SQLException {
        execute("""
                INSERT INTO harness_catalogue (harness, image, status, models, observed_at, pinned_image, asked_at)
                VALUES (?, ?, 'NO_CATALOGUE', '[]'::jsonb, now(), 'TEST-pinned@sha256:0', now())
                ON CONFLICT (harness) DO UPDATE SET image = excluded.image, status = excluded.status,
                       models = excluded.models, pinned_image = excluded.pinned_image""", CLAUDE, CLAUDE_IMAGE);

        HarnessCatalogues.Catalogue catalogue = configured().get(CLAUDE).orElseThrow();

        assertEquals(HarnessImageResult.Status.OK, catalogue.status());
        assertEquals("TEST-pinned@sha256:0", catalogue.pinnedImage());
        assertEquals("TEST-pinned@sha256:0", configured().imageFor(CLAUDE));
        assertEquals(List.of("TEST-cat-a"), slugs(catalogue));
    }

    @Test
    void codexStillReadsTheImageLabel() throws SQLException {
        execute("""
                INSERT INTO harness_catalogue (harness, image, status, models, observed_at, pinned_image, asked_at)
                VALUES (?, ?, 'NO_CATALOGUE', '[]'::jsonb, now(), NULL, now())
                ON CONFLICT (harness) DO UPDATE SET image = excluded.image, status = excluded.status,
                       models = excluded.models, pinned_image = excluded.pinned_image""", CODEX, CODEX_IMAGE);

        HarnessCatalogues.Catalogue catalogue = configured().get(CODEX).orElseThrow();

        assertEquals(HarnessImageResult.Status.NO_CATALOGUE, catalogue.status());
        assertEquals(List.of(), catalogue.offered());
        assertNull(configured().admit(CODEX, "TEST-cat-o", null).refusal(), "no catalogue refuses nothing, as before");
    }

    @Test
    void claudeCodeUnconfiguredHasNoCatalogue() {
        HarnessCatalogues unconfigured = catalogues(FactoryConfig.NOT_CONFIGURED, new CatalogTestDoubles.FakeManualModels());

        assertTrue(unconfigured.get(CLAUDE).isEmpty());
        assertNull(unconfigured.admit(CLAUDE, "TEST-cat-o", null).refusal());
    }

    @Test
    void claudeCodeWithAnEffortIsNotOffered() {
        assertEquals("effort_not_offered", configured().admit(CLAUDE, "TEST-cat-a", "high").refusal());
    }

    /** Codex's image lists four models: one fully priced, one missing REASONING, one RETIRED, one nobody knows. */
    private HarnessCatalogues.Catalogue codexWithFourModels() throws SQLException {
        execute("""
                INSERT INTO harness_catalogue (harness, image, status, models, observed_at, pinned_image, asked_at)
                VALUES (?, ?, 'OK', CAST(? AS jsonb), now(), NULL, now())
                ON CONFLICT (harness) DO UPDATE SET image = excluded.image, status = excluded.status,
                       models = excluded.models, pinned_image = excluded.pinned_image""", CODEX, CODEX_IMAGE,
                "[" + label("TEST-cat-o", 1) + "," + label("TEST-cat-a", 2) + "," + label("TEST-cat-retired", 3)
                        + "," + label("TEST-not-in-any-catalog", 4) + "]");
        return configured().get(CODEX).orElseThrow();
    }

    private static String label(String slug, int priority) {
        return "{\"slug\":\"" + slug + "\",\"displayName\":\"" + slug + "\",\"defaultEffort\":\"\",\"efforts\":[],"
                + "\"visible\":true,\"priority\":" + priority + "}";
    }

    private static List<String> offered(List<HarnessCatalogues.Offer> offers) {
        return offers.stream().map(offer -> offer.model().slug()).toList();
    }

    private static List<String> keyPriced(List<HarnessCatalogues.Offer> offers) {
        return offers.stream().filter(HarnessCatalogues.Offer::keyPriced).map(offer -> offer.model().slug()).toList();
    }

    @Test
    void aModelMissingFromTheCatalogAndTheManualTablesIsNotOffered() throws SQLException {
        List<HarnessCatalogues.Offer> offers = configured().offers(codexWithFourModels());

        assertTrue(!offered(offers).contains("TEST-not-in-any-catalog"), offered(offers).toString());
    }

    @Test
    void aRetiredModelIsNotOffered() throws SQLException {
        List<HarnessCatalogues.Offer> offers = configured().offers(codexWithFourModels());

        assertTrue(!offered(offers).contains("TEST-cat-retired"), offered(offers).toString());
    }

    /** Codex reports REASONING; TEST-cat-a has no reasoning rate, so a key cannot pay for it and a seat can. */
    @Test
    void anUnpricedModelIsNotKeyPricedButStaysOfferedForASeat() throws SQLException {
        List<HarnessCatalogues.Offer> offers = configured().offers(codexWithFourModels());

        assertEquals(List.of("TEST-cat-o", "TEST-cat-a"), offered(offers), "a seat may choose either");
        assertEquals(List.of("TEST-cat-o"), keyPriced(offers), "an API key may choose only the fully priced one");
    }

    @Test
    void aSwitchedOffManualModelHidesTheCatalogEntry() throws SQLException {
        HarnessCatalogues.Catalogue codex = codexWithFourModels();
        HarnessCatalogues shadowed = catalogues(CLAUDE_IMAGE, new CatalogTestDoubles.FakeManualModels()
                .with(CatalogTestDoubles.manual("TEST-cat-o", "openai", false)));

        assertTrue(!offered(shadowed.offers(codex)).contains("TEST-cat-o"));
    }

    /** Offering is narrower than admitting: a saved model that left the offer keeps its own refusal. */
    @Test
    void admitStillAnswersForAModelTheImageRunsButTheOfferLeftOut() throws SQLException {
        codexWithFourModels();

        assertNull(configured().admit(CODEX, "TEST-not-in-any-catalog", null).refusal(),
                "the image runs it: pricing refuses it later, with model_pricing_incomplete");
        assertEquals("model_not_run_by_harness", configured().admit(CODEX, "TEST-not-in-the-image", null).refusal());
    }

    private void execute(String sql, String... binds) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) {
                ps.setString(i + 1, binds[i]);
            }
            ps.executeUpdate();
        }
    }
}
