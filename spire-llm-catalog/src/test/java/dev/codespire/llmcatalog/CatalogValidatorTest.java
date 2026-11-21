package dev.codespire.llmcatalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static dev.codespire.llmcatalog.CatalogFixtures.SOURCE;
import static dev.codespire.llmcatalog.CatalogFixtures.TODAY;
import static dev.codespire.llmcatalog.CatalogFixtures.document;
import static dev.codespire.llmcatalog.CatalogFixtures.model;
import static dev.codespire.llmcatalog.CatalogFixtures.pricing;
import static dev.codespire.llmcatalog.CatalogFixtures.withAliases;
import static dev.codespire.llmcatalog.CatalogFixtures.withDisplayName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogValidatorTest {

    private static List<String> problemsOf(CatalogModel... models) {
        return CatalogValidator.problems(document(models), TODAY);
    }

    private static CatalogPricing withNotBilled(Long cacheWrite, List<String> notBilled) {
        return new CatalogPricing(1L, 2L, null, cacheWrite, null, null, notBilled, SOURCE, TODAY);
    }

    private static void assertOneProblemNaming(List<String> problems, String... words) {
        assertEquals(1, problems.size(), problems.toString());
        for (String word : words) {
            assertTrue(problems.getFirst().contains(word), problems.getFirst() + " should name " + word);
        }
    }

    @Test
    void aValidDocumentHasNoProblems() {
        assertEquals(List.of(), problemsOf(withAliases(model("TEST-a", "openai", pricing(1L, 2L)), "TEST-a-dated"),
                model("TEST-b", "anthropic", pricing(1L, 2L))));
    }

    @Test
    void theParsedFixtureIsValid() throws CatalogException {
        CatalogDocument doc = CatalogParser.parse(CatalogParserTest.VALID, CatalogParser.Mode.STRICT);

        assertEquals(List.of(), CatalogValidator.problems(doc, TODAY));
    }

    /** Every picker sorts and labels by the display name; a missing one is refused, not shown as null. */
    @Test
    void aMissingDisplayNameIsRefused() {
        assertOneProblemNaming(problemsOf(withDisplayName(model("TEST-a"), null)), "TEST-a", "displayName");
        assertOneProblemNaming(problemsOf(withDisplayName(model("TEST-a"), " ")), "TEST-a", "displayName");
    }

    @Test
    void inputAndOutputAreRequired() {
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", pricing(1L, null))), "TEST-a", "output");
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", pricing(null, 2L))), "TEST-a", "input");
    }

    @Test
    void aRateMustBePositiveAndAtMostTheCap() {
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", pricing(0L, 2L))), "TEST-a", "input");
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai",
                pricing(1L, CatalogLimits.MAX_RATE_MILLICENTS_PER_MILLION + 1))), "TEST-a", "output");
        assertEquals(List.of(), problemsOf(model("TEST-a", "openai",
                pricing(1L, CatalogLimits.MAX_RATE_MILLICENTS_PER_MILLION))));
    }

    @Test
    void aNotBilledTypeCarriesNoRate() {
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", withNotBilled(1L, List.of("CACHE_WRITE")))),
                "TEST-a", "CACHE_WRITE");
        assertEquals(List.of(), problemsOf(model("TEST-a", "openai", withNotBilled(null, List.of("CACHE_WRITE")))));
    }

    @Test
    void inputAndOutputCannotBeNotBilled() {
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", withNotBilled(null, List.of("INPUT")))),
                "TEST-a", "INPUT");
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", withNotBilled(null, List.of("OUTPUT")))),
                "TEST-a", "OUTPUT");
    }

    @Test
    void unknownNotBilledTypeIsAProblem() {
        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", withNotBilled(null, List.of("FOO")))),
                "TEST-a", "FOO");
    }

    @Test
    void providerMustBeListedAndAKnownVendor() {
        assertOneProblemNaming(problemsOf(model("TEST-a", "mistral", pricing(1L, 2L))), "TEST-a", "mistral");

        CatalogDocument unknownVendor = new CatalogDocument(
                List.of(CatalogFixtures.provider("openai"), CatalogFixtures.provider("mistral")), List.of());
        assertOneProblemNaming(CatalogValidator.problems(unknownVendor, TODAY), "mistral");
    }

    @Test
    void namesAreUniqueAcrossModelIdsAndAliases() {
        assertOneProblemNaming(problemsOf(model("TEST-a"), withAliases(model("TEST-b", "openai", pricing(1L, 2L)), "TEST-a")),
                "TEST-b", "TEST-a");
    }

    @Test
    void sourceUrlMustBeHttps() {
        CatalogPricing http = new CatalogPricing(1L, 2L, null, null, null, null, List.of(),
                "http://example.invalid/pricing", TODAY);

        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", http)), "TEST-a", "sourceUrl");
    }

    @Test
    void observedAtMayNotBeInTheFuture() {
        CatalogPricing future = new CatalogPricing(1L, 2L, null, null, null, null, List.of(), SOURCE,
                LocalDate.of(2026, 11, 21));

        assertOneProblemNaming(problemsOf(model("TEST-a", "openai", future)), "TEST-a", "observedAt");
    }

    @Test
    void anAbsentApiIsAllowedButAnApiWithoutItsOutputParamIsNot() {
        CatalogModel base = model("TEST-a");
        CatalogModel noApi = new CatalogModel(base.modelId(), base.provider(), base.displayName(), base.aliases(),
                base.lifecycle(), base.contextWindow(), base.maxOutputTokens(), base.capabilities(), null, base.pricing());
        CatalogModel noParam = new CatalogModel(base.modelId(), base.provider(), base.displayName(), base.aliases(),
                base.lifecycle(), base.contextWindow(), base.maxOutputTokens(), base.capabilities(),
                new CatalogApiDialect(null, true, null), base.pricing());

        assertEquals(List.of(), problemsOf(noApi));
        assertOneProblemNaming(problemsOf(noParam), "TEST-a", "outputTokenParam");
    }

    @Test
    void reasoningEffortIsNullOrLowMediumHigh() {
        CatalogModel base = model("TEST-a");
        CatalogModel xhigh = new CatalogModel(base.modelId(), base.provider(), base.displayName(), base.aliases(),
                base.lifecycle(), base.contextWindow(), base.maxOutputTokens(), base.capabilities(),
                new CatalogApiDialect(CatalogApiDialect.OutputTokenParam.MAX_TOKENS, false, "xhigh"), base.pricing());
        CatalogModel high = new CatalogModel(base.modelId(), base.provider(), base.displayName(), base.aliases(),
                base.lifecycle(), base.contextWindow(), base.maxOutputTokens(), base.capabilities(),
                new CatalogApiDialect(CatalogApiDialect.OutputTokenParam.MAX_TOKENS, false, "high"), base.pricing());

        assertOneProblemNaming(problemsOf(xhigh), "TEST-a", "reasoningEffort");
        assertEquals(List.of(), problemsOf(high));
    }
}
