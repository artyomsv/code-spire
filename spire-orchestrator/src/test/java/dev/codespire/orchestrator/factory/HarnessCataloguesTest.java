package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessImageResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The orchestrator's cache of which models each harness can run (M3.5 part M).
 *
 * <p>Model names are placeholders on purpose: this is about what the cache keeps and serves, and a list of
 * real names would go stale the day the vendor ships.
 */
@QuarkusTest
class HarnessCataloguesTest {

    @Inject HarnessCatalogues catalogues;
    @Inject FactoryConfig config;
    @Inject DataSource dataSource;

    private static final String HARNESS = "codex";

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM harness_catalogue");
        }
    }

    private String image() {
        return config.agentImage().get(HARNESS);
    }

    /**
     * The channel replays from its oldest record, so an answer can arrive after a newer one. The answer to
     * the NEWEST question is kept, whatever order they arrive in (review of PR #168).
     */
    @Test
    void anOlderAnswerArrivingLateDoesNotRollTheListBack() {
        java.time.Instant earlier = java.time.Instant.parse("2026-09-23T07:00:00Z");
        java.time.Instant later = earlier.plusSeconds(600);
        catalogues.record(new HarnessImageResult.Described("TEST-new", HARNESS, image(),
                HarnessImageResult.Status.OK, List.of(model("TEST-current", true, 1)), "TEST-pin-new", later));

        catalogues.record(new HarnessImageResult.Described("TEST-old", HARNESS, image(),
                HarnessImageResult.Status.OK, List.of(model("TEST-previous", true, 1)), "TEST-pin-old", earlier));
        // An answer from before times were sent is the oldest of all.
        catalogues.record(new HarnessImageResult.Described("TEST-untimed", HARNESS, image(),
                HarnessImageResult.Status.OK, List.of(model("TEST-untimed", true, 1)), "TEST-pin-untimed"));

        var kept = catalogues.get(HARNESS).orElseThrow();
        assertEquals("TEST-pin-new", kept.pinnedImage());
        assertTrue(kept.find("TEST-current").isPresent());
    }

    @Test
    void aNewerAnswerReplacesAnOlderOne() {
        java.time.Instant earlier = java.time.Instant.parse("2026-09-23T07:00:00Z");
        catalogues.record(new HarnessImageResult.Described("TEST-old", HARNESS, image(),
                HarnessImageResult.Status.OK, List.of(model("TEST-previous", true, 1)), "TEST-pin-old", earlier));

        catalogues.record(new HarnessImageResult.Described("TEST-new", HARNESS, image(),
                HarnessImageResult.Status.OK, List.of(model("TEST-current", true, 1)), "TEST-pin-new",
                earlier.plusSeconds(600)));

        assertEquals("TEST-pin-new", catalogues.get(HARNESS).orElseThrow().pinnedImage());
    }

    /**
     * A run uses the exact image the list was read from; with nothing read, the tag, as before part M.
     * And an answer about an image the harness has since left pins nothing (review of PR #167).
     */
    @Test
    void aRunUsesTheImageTheListWasReadFromAndTheTagWhenNothingWasRead() {
        assertEquals(image(), catalogues.imageFor(HARNESS), "nothing read yet: the tag");

        catalogues.record(new HarnessImageResult.Described("TEST-request", HARNESS, image(),
                HarnessImageResult.Status.OK, List.of(model("TEST-model", true, 1)), "TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals("TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000", catalogues.imageFor(HARNESS));
        assertEquals("TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000", catalogues.admit(HARNESS, "TEST-model", null).image());

        catalogues.record(new HarnessImageResult.Described("TEST-request-2", HARNESS, "TEST-another-image:1",
                HarnessImageResult.Status.OK, List.of(model("TEST-model", true, 1)), "TEST-another@sha256:" + "1".repeat(64)));
        assertEquals("TEST-registry.invalid/agent@sha256:0000000000000000000000000000000000000000000000000000000000000000", catalogues.imageFor(HARNESS), "an answer about another image changes nothing");
    }

    private static HarnessImageResult.Model model(String slug, boolean visible, int priority) {
        return new HarnessImageResult.Model(slug, slug, "medium", List.of("low", "medium", "high"), visible, priority);
    }

    private HarnessImageResult.Described answer(String image, HarnessImageResult.Status status,
                                                List<HarnessImageResult.Model> models) {
        return new HarnessImageResult.Described("TEST-request", HARNESS, image, status, models);
    }

    /** "Not asked yet" and "the image declares nothing" send an operator to different places. */
    @Test
    void nothingHeardYetIsAbsentRatherThanAnEmptyList() {
        assertTrue(catalogues.get(HARNESS).isEmpty());
    }

    @Test
    void anAnswerIsKeptAndServed() {
        catalogues.record(answer(image(), HarnessImageResult.Status.OK,
                List.of(model("TEST-a", true, 2), model("TEST-b", true, 1))));

        var catalogue = catalogues.get(HARNESS).orElseThrow();
        assertEquals(HarnessImageResult.Status.OK, catalogue.status());
        assertEquals(2, catalogue.models().size());
    }

    /** Offered means the vendor's shown ones, in the vendor's own order — not insertion order. */
    @Test
    void theOfferedListIsTheVendorsShownModelsInTheVendorsOrder() {
        catalogues.record(answer(image(), HarnessImageResult.Status.OK, List.of(
                model("TEST-third", true, 30), model("TEST-hidden", false, 1), model("TEST-first", true, 10))));

        var offered = catalogues.get(HARNESS).orElseThrow().offered();

        assertEquals(List.of("TEST-first", "TEST-third"), offered.stream().map(HarnessImageResult.Model::slug).toList());
    }

    /** A hidden model is not OFFERED, but it still runs if named — so it must still be findable. */
    @Test
    void aHiddenModelIsNotOfferedButIsStillKnown() {
        catalogues.record(answer(image(), HarnessImageResult.Status.OK, List.of(model("TEST-hidden", false, 1))));

        var catalogue = catalogues.get(HARNESS).orElseThrow();
        assertTrue(catalogue.offered().isEmpty());
        assertTrue(catalogue.find("TEST-hidden").isPresent());
    }

    /**
     * An answer about an image the harness no longer runs is dropped.
     *
     * <p>A different tag of the same repository may carry a different CLI and a different list. Keeping an
     * answer that arrived after the configuration moved on would offer models that will not run.
     */
    @Test
    void anAnswerAboutAnotherImageIsNotKept() {
        catalogues.record(answer("TEST-some-other-image:tag", HarnessImageResult.Status.OK,
                List.of(model("TEST-stale", true, 1))));

        assertTrue(catalogues.get(HARNESS).isEmpty());
    }

    /** And the empty cases keep their reason, which is the whole point of storing a status. */
    @Test
    void anImageWithNoCatalogueSaysSoRatherThanLookingEmpty() {
        catalogues.record(answer(image(), HarnessImageResult.Status.NO_CATALOGUE, List.of()));

        var catalogue = catalogues.get(HARNESS).orElseThrow();
        assertEquals(HarnessImageResult.Status.NO_CATALOGUE, catalogue.status());
        assertTrue(catalogue.models().isEmpty());
    }

    /** A newer answer replaces the older one whole, including a change of status. */
    @Test
    void aNewerAnswerReplacesTheOlderOne() {
        catalogues.record(answer(image(), HarnessImageResult.Status.OK, List.of(model("TEST-old", true, 1))));
        catalogues.record(answer(image(), HarnessImageResult.Status.UNREADABLE, List.of()));

        var catalogue = catalogues.get(HARNESS).orElseThrow();
        assertEquals(HarnessImageResult.Status.UNREADABLE, catalogue.status());
        assertTrue(catalogue.find("TEST-old").isEmpty(), "nothing of the old list may survive a replacement");
    }
}
