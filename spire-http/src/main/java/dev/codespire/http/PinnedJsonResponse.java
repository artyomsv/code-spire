package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Transient JSON and response metadata; the JDK response header map is immutable. */
public record PinnedJsonResponse(JsonNode body, Map<String, List<String>> headers) {}
