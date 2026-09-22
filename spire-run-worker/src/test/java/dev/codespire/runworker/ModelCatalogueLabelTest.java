package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.HarnessImageResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the model catalogue an agent image declares (M3.5 part M).
 *
 * <p>The shape below is what {@code deploy/agent/build-codex.sh} writes. The model names are placeholders
 * on purpose: the point is the parsing, and a list of real names would go stale the day the vendor ships.
 */
class ModelCatalogueLabelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String label(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ModelCatalogueLabel.Read read(String json) {
        return ModelCatalogueLabel.of(Map.of(ModelCatalogueLabel.LABEL, label(json)), MAPPER);
    }

    /** A level no later hop would accept makes the list unreadable, not a list that fails a day later. */
    @Test
    void aLevelOfTheWrongShapeMakesTheWholeLabelUnreadable() {
        assertEquals(HarnessImageResult.Status.UNREADABLE,
                read("[{\"s\":\"TEST-fast\",\"d\":\"low\",\"e\":[\"low\",\"x-high\"]}]").status());
    }

    @Test
    void aWellFormedCatalogueYieldsEveryModelWithItsOwnLevels() {
        var read = read("""
                [{"s":"TEST-fast","n":"Test Fast","d":"low","e":["low","medium","high"],"v":"list","p":1},
                 {"s":"TEST-deep","n":"Test Deep","d":"high","e":["medium","high","xhigh"],"v":"hide","p":9}]
                """);

        assertEquals(HarnessImageResult.Status.OK, read.status());
        assertEquals(List.of("TEST-fast", "TEST-deep"), read.models().stream().map(HarnessImageResult.Model::slug).toList());
        var fast = read.models().getFirst();
        assertEquals("low", fast.defaultEffort(), "each model carries ITS OWN default, not a global one");
        assertEquals(List.of("low", "medium", "high"), fast.efforts());
        assertTrue(fast.visible());
        assertFalse(read.models().get(1).visible(), "hide is read as not offered, not as absent");
    }

    /**
     * A plain `docker build` of the Dockerfile sets the label from an EMPTY build argument, so the key is
     * present with nothing in it. That is "this image declares nothing", not "this image is broken".
     */
    @Test
    void aBlankLabelIsNoCatalogueRatherThanUnreadable() {
        assertEquals(HarnessImageResult.Status.NO_CATALOGUE,
                ModelCatalogueLabel.of(Map.of(ModelCatalogueLabel.LABEL, ""), MAPPER).status());
        assertEquals(HarnessImageResult.Status.NO_CATALOGUE, ModelCatalogueLabel.of(Map.of(), MAPPER).status());
    }

    @Test
    void somethingThatIsNotBase64IsUnreadable() {
        var read = ModelCatalogueLabel.of(Map.of(ModelCatalogueLabel.LABEL, "not base64 at all!"), MAPPER);
        assertEquals(HarnessImageResult.Status.UNREADABLE, read.status());
        assertTrue(read.models().isEmpty());
    }

    @Test
    void base64ThatIsNotACatalogueIsUnreadable() {
        assertEquals(HarnessImageResult.Status.UNREADABLE, read("{\"not\":\"an array\"}").status());
        assertEquals(HarnessImageResult.Status.UNREADABLE, read("[]").status(), "an empty list declares nothing usable");
    }

    /**
     * One broken entry spoils the whole label rather than yielding the entries that parsed.
     *
     * <p>A list with a silent hole in it looks exactly like a complete list, and the model an operator is
     * looking for is always the one in the hole.
     */
    @Test
    void oneEntryWithoutASlugMakesTheWholeLabelUnreadable() {
        var read = read("""
                [{"s":"TEST-fine","d":"low","e":["low"],"v":"list","p":1},
                 {"n":"no slug here","d":"low","e":["low"]}]
                """);

        assertEquals(HarnessImageResult.Status.UNREADABLE, read.status());
        assertTrue(read.models().isEmpty(), "partial answers are not answers");
    }

    /** Missing optional fields do not make an entry unusable; missing the slug does. */
    @Test
    void aModelWithOnlyASlugIsStillAModel() {
        var model = read("[{\"s\":\"TEST-bare\"}]").models().getFirst();

        assertEquals("TEST-bare", model.displayName(), "the slug stands in for a missing display name");
        assertEquals(List.of(), model.efforts());
        assertTrue(model.visible(), "no visibility stated reads as offered");
    }
}
