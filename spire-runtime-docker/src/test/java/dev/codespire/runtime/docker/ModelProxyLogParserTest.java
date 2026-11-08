package dev.codespire.runtime.docker;

import dev.codespire.runtime.ModelProxyReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The model proxy's report lines, {@code spire-model <METHOD> <path> -> <status|refused|aborted>}, read back. */
class ModelProxyLogParserTest {

    @Test
    void aRefusedCredentialOnTheModelCallIsRejected() {
        ModelProxyReport report = ModelProxyLogParser.parse(List.of(
                "spire-model POST /v1/responses -> 401",
                "spire-model POST /backend-api/codex/responses -> 401"));

        assertEquals(new ModelProxyReport(2, 2, List.of()), report);
    }

    @Test
    void a401OffTheModelCallAndA403AreNotRejections() {
        // A seat's account check can answer 401 while the seat still works, and a 403 can be a challenge or an
        // entitlement answer: marking either would retire a healthy pool member for good.
        ModelProxyReport report = ModelProxyLogParser.parse(List.of(
                "spire-model GET /backend-api/wham/accounts/check -> 401",
                "spire-model POST /v1/responses -> 403",
                "spire-model POST /v1/responses -> 200"));

        assertEquals(new ModelProxyReport(3, 0, List.of()), report);
    }

    @Test
    void aRefusedPathIsRecordedOnce() {
        ModelProxyReport report = ModelProxyLogParser.parse(List.of(
                "spire-model GET /v1/files -> refused",
                "spire-model GET /v1/files -> refused",
                "spire-model GET /v1/models -> refused",
                "spire-model POST /v1/responses -> aborted"));

        assertEquals(new ModelProxyReport(4, 0, List.of("/v1/files", "/v1/models")), report);
    }

    @Test
    void otherLinesAreIgnored() {
        ModelProxyReport report = ModelProxyLogParser.parse(List.of(
                "spire-model: SPIRE_MODEL_SECRET is required",
                "Exception in thread main",
                "spire-model POST /v1/responses -> maybe",
                "spire-model POST /v1/responses ->  401",
                "xspire-model POST /v1/responses -> 401",
                "spire-model POST /v1/responses -> 401 trailing"));

        assertEquals(ModelProxyReport.NONE, report);
    }

    @Test
    void theRefusedPathsAreBounded() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < ModelProxyLogParser.MAX_REFUSED_PATHS + 50; i++) {
            lines.add("spire-model GET /v1/p" + i + " -> refused");
        }

        ModelProxyReport report = ModelProxyLogParser.parse(lines);

        assertEquals(ModelProxyLogParser.MAX_REFUSED_PATHS, report.refusedPaths().size());
        assertEquals("/v1/p0", report.refusedPaths().getFirst());
        assertEquals(256, ModelProxyLogParser.MAX_REFUSED_PATHS);
    }

    @Test
    void theLogIsReadOnlyToItsCharacterBound() {
        assertEquals(8 * 1024 * 1024, ModelProxyLogParser.MAX_LOG_CHARS);
        String line = "spire-model POST /v1/responses -> 401";
        List<String> lines = new ArrayList<>();
        int fitting = ModelProxyLogParser.MAX_LOG_CHARS / (line.length() + 1);
        for (int i = 0; i < fitting + 100; i++) {
            lines.add(line);
        }

        ModelProxyReport report = ModelProxyLogParser.parse(lines);

        assertEquals(fitting, report.requests(), "lines past the bound are not read");
    }

    @Test
    void anOverlongLineIsIgnored() {
        String path = "/v1/" + "a".repeat(ModelProxyLogParser.MAX_LINE_CHARS);

        assertEquals(ModelProxyReport.NONE, ModelProxyLogParser.parse(List.of("spire-model GET " + path + " -> refused")));
    }
}
