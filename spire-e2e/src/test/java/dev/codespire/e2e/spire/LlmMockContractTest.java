package dev.codespire.e2e.spire;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the mock's own contract, with none of our services in the picture.
 *
 * <p>A mock that answers the wrong SHAPE produces a review failure three layers away, so it is far
 * cheaper to prove it here. Four properties matter, and each corresponds to a mistake this design
 * actually made before it ran:
 *
 * <ol>
 *   <li>The three call kinds are told apart by {@code PromptCatalog.lockedSystemSuffix}, chosen
 *       because it is LOCKED: per-repository prompt customization can rewrite a persona or body, so
 *       matching on those would break the suite the day an operator overrides a prompt.</li>
 *   <li>A defect marker counts only on an ADDED line. After a fix commit the deleted line appears in
 *       the incremental diff as a REMOVED line, so naive presence-matching reports the finding
 *       precisely when the defect is gone.</li>
 *   <li>An added line is rendered {@code <lineNumber> +<content>} (DiffRenderer), NOT with the sign
 *       at the start of the line. A pattern anchored to {@code ^\+} matches nothing at all, and the
 *       mock then silently answers "clean" for every review.</li>
 *   <li>A re-review is recognised by a prior finding appearing in the already-reported block. The
 *       block's HEADING is in the template unconditionally, so the heading is not a discriminator —
 *       the finding messages carry their own sentinel ({@code E2E-FINDING-}) which the code markers
 *       ({@code E2E-DEFECT-}) deliberately do not share.</li>
 * </ol>
 */
class LlmMockContractTest {

    private static final String REVIEW = "one-paragraph overall assessment";

    private static final String RECONCILE = "Respond ONLY with JSON:";

    private static final String FOLLOWUP = "Respond with ONLY the reply to post in the thread";

    /** Exactly how DiffRenderer writes an added line: line number, space, '+', content. */
    private static final String ADDED_A = "7 +        return numerator / denominator;  // E2E-DEFECT-A";

    /**
     * The reconcile prompt carries a RAW unified diff, not DiffRenderer's output.
     *
     * <p>{@code ReviewWorker.reconcile} passes the incremental compare diff through unrendered when
     * the compare succeeds, so removed lines are plain {@code -} at line start — NOT the
     * {@code <lineNumber> -<content>} the review prompt uses. A pattern written for the review shape
     * matches nothing here, and the mock then silently answers with the fallback verdicts: every
     * finding UNCHANGED, which is indistinguishable from a review where nothing was fixed.
     */
    private static final String REMOVED_A = "\n-        return numerator / denominator;  // E2E-DEFECT-A";

    private static final String REMOVED_B = "\n-        return values[index];  // E2E-DEFECT-B";

    /** The review prompt's shape, for contrast: the sign follows the line number. */
    private static final String RENDERED_REMOVED_A =
            "7 -        return numerator / denominator;  // E2E-DEFECT-A";

    private static final String ADDED_D = "9 +export function widened(rows: any) {  // E2E-DEFECT-D";

    private static final String ALREADY_REPORTED =
            "- src/main/java/e2e/Defects.java:7 - E2E-FINDING-A division by zero when the denominator is zero.";

    @Test
    void servesTheModelListThatProviderRegistrationValidatesAgainst() {
        assertTrue(get("/v1/models").contains("e2e-mock-model"),
                "registerLlmProvider validates the key with GET {baseUrl}/models (LlmKeyValidator), so "
                        + "this stub is a prerequisite of setup rather than a convenience");
    }

    @Test
    void aFirstReviewWithAnAddedMarkerReturnsTheThreeFindings() {
        String content = completion(REVIEW + "\n" + ADDED_A);

        assertTrue(content.contains("\"findings\""), content);
        assertTrue(content.contains("E2E-FINDING-A"), content);
        assertTrue(content.contains("E2E-FINDING-B"), content);
        assertTrue(content.contains("E2E-FINDING-C"), content);
    }

    @Test
    void aRemovedMarkerLineDoesNotProduceAFinding() {
        String content = completion(REVIEW + "\n" + RENDERED_REMOVED_A);

        assertFalse(content.contains("E2E-FINDING-A"),
                "a removed marker line must not read as a present defect: " + content);
    }

    @Test
    void aReReviewReportsNothingAlreadyReported() {
        String content = completion(REVIEW + "\n" + ALREADY_REPORTED + "\n" + ADDED_A);

        assertTrue(content.contains("\"findings\":[]"),
                "with prior findings listed as already reported, the review must add nothing: " + content);
    }

    @Test
    void aReReviewStillRaisesANewlyIntroducedDefect() {
        String content = completion(REVIEW + "\n" + ALREADY_REPORTED + "\n" + ADDED_D);

        assertTrue(content.contains("E2E-FINDING-D"), content);
        assertFalse(content.contains("E2E-FINDING-A"),
                "a new defect must not drag the already-reported ones back in: " + content);
    }

    @Test
    void aReconcilePromptReturnsVerdictsNotFindings() {
        String content = completion(RECONCILE + REMOVED_A);

        assertTrue(content.contains("\"verdicts\""), content);
        assertFalse(content.contains("\"findings\""), content);
        assertTrue(content.contains("still-open"),
                "the partial-fix round must leave one finding STILL_OPEN — the only verdict that can "
                        + "fail if the incremental diff parses to zero files: " + content);
    }

    /**
     * The fallback says UNCHANGED, not RESOLVED, and that distinction is load-bearing.
     *
     * <p>A {@code /review} re-run happens on the SAME commit, so nothing was fixed — but it still
     * takes the reconcile path. While the fallback said "resolved", that re-run closed every finding
     * and posted "Fixed in &lt;sha&gt;" against code nobody had touched, which then left the later
     * partial-fix scenario with nothing open to reconcile.
     */
    @Test
    void aReconcileWithNothingRemovedSaysUnchanged() {
        String content = completion(RECONCILE + "\nnothing removed here");

        assertTrue(content.contains("\"verdicts\""), content);
        assertTrue(content.contains("unchanged"), content);
        assertFalse(content.contains("resolved"),
                "nothing was removed, so nothing was fixed: " + content);
        assertFalse(content.contains("still-open"), content);
    }

    @Test
    void aReconcileSeeingTheSecondMarkerRemovedResolvesEverything() {
        String content = completion(RECONCILE + REMOVED_B);

        assertTrue(content.contains("resolved"), content);
        assertFalse(content.contains("still-open"), content);
    }

    @Test
    void aFollowUpReturnsFencedProseNotJson() {
        String content = completion(FOLLOWUP + "\nthe author asks a question");

        assertFalse(content.strip().startsWith("{"), "a follow-up reply is prose, not JSON: " + content);
        assertTrue(content.contains("```"),
                "the locked FOLLOWUP contract requires a fence — indented code renders as prose on the "
                        + "SCM, which is a defect this project already shipped once");
    }

    private static String completion(String systemPrompt) {
        String body = "{\"model\":\"e2e-mock-model\",\"messages\":["
                + "{\"role\":\"system\",\"content\":" + Json.write(systemPrompt) + "},"
                + "{\"role\":\"user\",\"content\":\"diff\"}]}";
        JsonNode reply = Json.read(post("/v1/chat/completions", body));
        return reply.get("choices").get(0).get("message").get("content").asText();
    }

    private static String base() {
        return Stack.llmMockAdminUrl().replace("/__admin", "");
    }

    private static String get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).GET());
    }

    private static String post(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String send(HttpRequest.Builder builder) {
        HttpRequest built = builder.timeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<String> response =
                    Stack.http().send(built, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("llm-mock " + response.statusCode()
                        + " for " + built.uri() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("llm-mock unreachable at " + built.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling llm-mock", e);
        }
    }
}
