package dev.codespire.contract.review;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code omittedFiles} on a nested wire type that {@code ContractSchemaSnapshotTest} does not reach, and that the
 * worker also stores in its idempotency table. Both directions of a rolling upgrade must decode.
 */
class ReviewResultWireTest {

    // As the services read the bus and the idempotency store: Quarkus leaves FAIL_ON_UNKNOWN_PROPERTIES off.
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The shape an older service decodes into. */
    record OldReviewResult(List<Finding> findings, String summary, ModelUsage usage,
                           boolean truncated, boolean degraded) {
    }

    @Test
    void anOldResultDecodesWithNoOmittedFiles() throws Exception {
        String legacy = """
                {"findings":[],"summary":"TEST summary","usage":null,"truncated":true,"degraded":false}""";

        ReviewResult back = mapper.readValue(legacy, ReviewResult.class);

        assertEquals(List.of(), back.omittedFiles(), "never null, so every reader can iterate it");
        assertTrue(back.truncated());
    }

    @Test
    void aNewResultDecodesIntoTheOldShape() throws Exception {
        ReviewResult current = new ReviewResult(List.of(), "TEST summary", null, true, false)
                .withOmittedFiles(List.of("src/TEST-A.java"));

        OldReviewResult old = mapper.readValue(mapper.writeValueAsString(current), OldReviewResult.class);

        assertTrue(old.truncated(), "an older reader still sees the review as partial");
        assertEquals("TEST summary", old.summary());
    }

    @Test
    void omittedFilesRoundTrip() throws Exception {
        ReviewResult current = new ReviewResult(List.of(), "TEST summary", null, true, false)
                .withOmittedFiles(List.of("src/TEST-A.java", "src/TEST-B.java"));

        assertEquals(current, mapper.readValue(mapper.writeValueAsString(current), ReviewResult.class));
    }

    /** CLAUDE.md, "Adding a component to a wire record": every wither keeps the new component. */
    @Test
    void everyWitherKeepsTheOmittedFiles() {
        List<String> omitted = List.of("src/TEST-A.java");
        ReviewResult result = new ReviewResult(List.of(), "TEST summary", null, true, false).withOmittedFiles(omitted);

        assertEquals(omitted, result.withTruncated(true).omittedFiles());
        assertEquals(omitted, result.withSummary("TEST other").omittedFiles());
        assertEquals(omitted, result.withFindings(List.of()).omittedFiles());
    }

    @Test
    void namingOmittedFilesDoesNotItselfMarkTheResultTruncated() {
        ReviewResult result = new ReviewResult(List.of(), "TEST summary", null).withOmittedFiles(List.of("src/TEST-A.java"));

        assertFalse(result.truncated());
    }
}
