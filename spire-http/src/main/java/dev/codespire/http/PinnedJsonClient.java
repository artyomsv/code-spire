package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read-only facade. The shared internal transport owns redirects, authentication and byte bounds. */
public class PinnedJsonClient {
    public static final int MAX_RAW_BYTES = PinnedHttpTransport.MAX_RAW_BYTES;
    private final PinnedHttpTransport transport;

    public PinnedJsonClient(PinnedJsonConfig config, ObjectMapper mapper, HttpFailures failures) {
        transport = new PinnedHttpTransport(config, mapper, failures);
    }
    public JsonNode getJson(String path) { return transport.getJson(path); }
    public String getRaw(String path) { return transport.getRaw(path); }
    public JsonNode getIdentityJson(String path) { return transport.getIdentityJson(path); }
    /** Strict origin pinning, including response headers needed to exhaust an evidence audit. */
    public PinnedJsonResponse getIdentityResponse(String path) { return transport.identityResponse(path); }
}
