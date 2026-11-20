package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.HarnessImageResult;
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

    @AfterEach
    void removeTheRows() throws SQLException {
        execute("DELETE FROM harness_catalogue WHERE image IN (?, ?)", CLAUDE_IMAGE, CODEX_IMAGE);
    }

    private HarnessCatalogues catalogues(String claudeImage, CatalogTestDoubles.FakeManualModels manual) {
        HarnessCatalogues catalogues = new HarnessCatalogues();
        catalogues.dataSource = dataSource;
        catalogues.mapper = mapper;
        catalogues.models = CatalogTestDoubles.testCatalog(manual);
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

    private void execute(String sql, String... binds) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) {
                ps.setString(i + 1, binds[i]);
            }
            ps.executeUpdate();
        }
    }
}
