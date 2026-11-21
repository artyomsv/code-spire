package dev.codespire.llmcatalog;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads a catalog file. STRICT (CI) refuses a field it does not know; LENIENT (the app) ignores it,
 * so an install that is not upgraded keeps reading a newer {@code v1} file.
 */
public final class CatalogParser {

    public enum Mode { STRICT, LENIENT }

    private static final ObjectMapper STRICT_MAPPER = mapper(Mode.STRICT);
    private static final ObjectMapper LENIENT_MAPPER = mapper(Mode.LENIENT);

    private CatalogParser() {
    }

    public static CatalogDocument parse(String yaml, Mode mode) throws CatalogException {
        if (yaml == null) {
            throw new CatalogException("the catalog file is empty");
        }
        if (yaml.getBytes(StandardCharsets.UTF_8).length > CatalogLimits.MAX_BYTES) {
            throw new CatalogException("the catalog file is larger than " + CatalogLimits.MAX_BYTES + " bytes");
        }
        refuseAnchorsAliasesAndTags(yaml);
        return bind(yaml, mode);
    }

    /**
     * Jackson's YAML parser never runs SnakeYAML's composer, so LoaderOptions cannot refuse these.
     * The event stream is read on its own first: an anchor, an alias or an explicit tag is refused.
     */
    private static void refuseAnchorsAliasesAndTags(String yaml) throws CatalogException {
        try {
            for (Event event : new Yaml(new LoaderOptions()).parse(new StringReader(yaml))) {
                checkEvent(event);
            }
        } catch (YAMLException e) {
            throw new CatalogException("the catalog file is not valid YAML");
        }
    }

    private static void checkEvent(Event event) throws CatalogException {
        if (event instanceof AliasEvent) {
            throw new CatalogException("YAML aliases are not allowed in the catalog file");
        }
        if (event instanceof NodeEvent node && node.getAnchor() != null) {
            throw new CatalogException("YAML anchors are not allowed in the catalog file");
        }
        if (event instanceof ScalarEvent scalar && scalar.getTag() != null) {
            throw new CatalogException("YAML tags are not allowed in the catalog file");
        }
        if (event instanceof CollectionStartEvent collection && collection.getTag() != null) {
            throw new CatalogException("YAML tags are not allowed in the catalog file");
        }
    }

    private static CatalogDocument bind(String yaml, Mode mode) throws CatalogException {
        ObjectMapper mapper = mode == Mode.STRICT ? STRICT_MAPPER : LENIENT_MAPPER;
        try {
            CatalogDocument document = mapper.readValue(yaml, CatalogDocument.class);
            if (document == null) {
                throw new CatalogException("the catalog file is empty");
            }
            return document;
        } catch (UnrecognizedPropertyException e) {
            throw new CatalogException("an unknown field in the catalog file, line "
                    + e.getLocation().getLineNr());
        } catch (JsonProcessingException e) {
            throw new CatalogException("the catalog file does not match the catalog format"
                    + duplicateHint(e));
        }
    }

    private static String duplicateHint(JsonProcessingException e) {
        String message = e.getOriginalMessage();
        return message != null && message.startsWith("Duplicate field") ? " (a duplicate key)" : "";
    }

    /**
     * A fraction is never coerced to a whole number: Jackson would bind 1.9 to 1, and a price is a whole number of
     * millicents, so a fractional one is refused rather than silently changed.
     */
    private static ObjectMapper mapper(Mode mode) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, mode == Mode.STRICT);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
