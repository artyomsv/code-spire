package dev.codespire.orchestrator.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import dev.codespire.orchestrator.llm.catalog.ResolvedModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reviews list's vendor badge comes from the resolver, so a catalog model (no llm_model row) shows
 * its vendor, and a resolver fault costs the badge, never the list.
 */
@QuarkusTest
class ReviewListVendorBadgeTest {

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    @Inject
    AttentionBroadcaster attention;

    private long seedReviewChargedOn(String model) {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithoutCharges(projection, pr);
        projection.recordCharges(ChargeCall.forReview(ReviewFixtures.reviewIdFor(pr), "TEST-BADGE-" + pr,
                ChargeKind.REVIEW, model, List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 1))));
        return pr;
    }

    private static ReviewSummary rowFor(ReviewProjection reader, long pr) {
        return reader.listSummaries(false).stream().filter(summary -> summary.pr() == pr).findFirst()
                .orElseThrow(() -> new AssertionError("no list row for PR " + pr));
    }

    @Test
    void reviewListShowsTheVendorOfACatalogModel() {
        long pr = seedReviewChargedOn("TEST-cat-a");

        ReviewSummary row = rowFor(projection, pr);

        assertEquals("TEST-cat-a", row.model());
        assertEquals("anthropic", row.llmType());
    }

    /** An unknown model's badge is null, and null is remembered: one resolve per model for the whole list. */
    @Test
    void anUnknownModelIsResolvedOncePerList() {
        String unknown = "TEST-badge-unknown-" + ReviewFixtures.newPr();
        seedReviewChargedOn(unknown);
        seedReviewChargedOn(unknown);
        AtomicInteger asked = new AtomicInteger();
        ReviewProjection counting = new ReviewProjection();
        counting.dataSource = dataSource;
        counting.mapper = mapper;
        counting.encryption = encryption;
        counting.attention = attention;
        counting.catalog = new LlmModelCatalog() {
            @Override
            public Optional<ResolvedModel> resolve(String name) {
                if (unknown.equals(name)) {
                    asked.incrementAndGet();
                }
                return Optional.empty();
            }
        };

        counting.listSummaries(false);

        assertEquals(1, asked.get(), "two rows on one unknown model, so one resolve");
    }

    @Test
    void reviewListSurvivesAResolverFault() {
        long pr = seedReviewChargedOn("TEST-cat-a");
        ReviewProjection overAFailingCatalog = new ReviewProjection();
        overAFailingCatalog.dataSource = dataSource;
        overAFailingCatalog.mapper = mapper;
        overAFailingCatalog.encryption = encryption;
        overAFailingCatalog.attention = attention;
        overAFailingCatalog.catalog = CatalogTestDoubles.failingCatalog();

        ReviewSummary row = rowFor(overAFailingCatalog, pr);

        assertEquals("TEST-cat-a", row.model());
        assertEquals("", row.llmType(), "a fault costs the badge, not the list");
    }
}
