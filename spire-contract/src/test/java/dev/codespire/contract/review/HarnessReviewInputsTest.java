package dev.codespire.contract.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The files a harness review's agent reads at {@code /inputs}: plain Markdown names, one file per name, a total under
 * the contract cap, and never their text in a log line. {@code ContractSchemaSnapshotTest} does not recurse, so this
 * nested wire type has its own round trip.
 */
class HarnessReviewInputsTest {

    private static HarnessReviewInputs one(String name, String text) {
        return new HarnessReviewInputs(List.of(new HarnessReviewInputs.InputFile(name, text)));
    }

    @Test
    void anInputFileNameMustBeAPlainMarkdownName() {
        for (String name : List.of("../x.md", "a/b.md", "x.txt", "Context.md")) {
            assertThrows(IllegalArgumentException.class, () -> one(name, "TEST"), name);
        }
        assertDoesNotThrow(() -> one(HarnessReviewInputs.CONTEXT, "TEST"));
        assertDoesNotThrow(() -> one(HarnessReviewInputs.PRIOR_FINDINGS, "TEST"));
    }

    @Test
    void twoFilesWithOneNameAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new HarnessReviewInputs(List.of(
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-A"),
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-B"))));
    }

    @Test
    void inputsOverTheCapAreRefused() {
        // Two files, so the cap is on the total and not per file.
        String half = "x".repeat(HarnessReviewInputs.MAX_BYTES / 2);

        assertDoesNotThrow(() -> new HarnessReviewInputs(List.of(
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, half),
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.PRIOR_FINDINGS, half))));
        assertThrows(IllegalArgumentException.class, () -> new HarnessReviewInputs(List.of(
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, half),
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.PRIOR_FINDINGS, half + "x"))));
    }

    @Test
    void theCapCountsUtf8BytesNotCharacters() {
        // A 3-byte character: MAX_BYTES / 3 of them fit, one more does not.
        String atCap = "€".repeat(HarnessReviewInputs.MAX_BYTES / 3);

        assertDoesNotThrow(() -> one(HarnessReviewInputs.CONTEXT, atCap));
        assertThrows(IllegalArgumentException.class, () -> one(HarnessReviewInputs.CONTEXT, atCap + "€"));
    }

    @Test
    void inputsNeverPrintTheirText() {
        HarnessReviewInputs inputs = one(HarnessReviewInputs.CONTEXT, "TEST-SECRET-TEXT");

        String printed = inputs.toString();

        assertTrue(printed.contains("context.md"), printed);
        assertTrue(printed.contains("16"), "the byte count of TEST-SECRET-TEXT: " + printed);
        assertFalse(printed.contains("TEST-SECRET-TEXT"), printed);
    }

    @Test
    void theInputsRoundTripEveryFile() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HarnessReviewInputs inputs = new HarnessReviewInputs(List.of(
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-CONTEXT"),
                new HarnessReviewInputs.InputFile(HarnessReviewInputs.PRIOR_FINDINGS, "TEST-PRIOR")));

        HarnessReviewInputs decoded = mapper.readValue(mapper.writeValueAsString(inputs), HarnessReviewInputs.class);

        assertEquals(inputs, decoded);
        assertEquals("TEST-PRIOR", decoded.asMap().get(HarnessReviewInputs.PRIOR_FINDINGS));
    }

    @Test
    void noInputsAreNoFiles() {
        assertEquals(List.of(), HarnessReviewInputs.NONE.files());
        assertTrue(HarnessReviewInputs.NONE.asMap().isEmpty());
    }
}
