package dev.codespire.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One mapper for the whole harness. Both drivers speak JSON and neither needs its own. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static JsonNode read(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + abbreviate(body), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize " + value, e);
        }
    }

    /** Failure messages carry response bodies, and an unabbreviated diff buries the actual error. */
    private static String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        return body.length() <= 400 ? body : body.substring(0, 400) + "… (" + body.length() + " chars)";
    }
}
