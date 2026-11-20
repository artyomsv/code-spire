package dev.codespire.llmcatalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The catalog every install reads by default must pass the strict rules CI applies. */
class RepositoryCatalogTest {

    private static final Path CATALOG = Path.of("..", "ai-catalog", "v1", "catalog.yaml");

    @Test
    void theRepositoryCatalogIsStrictlyValid() throws IOException, CatalogException {
        CatalogDocument doc = CatalogParser.parse(Files.readString(CATALOG, StandardCharsets.UTF_8),
                CatalogParser.Mode.STRICT);

        assertEquals(List.of(), CatalogValidator.problems(doc, LocalDate.now(ZoneOffset.UTC)));
    }
}
