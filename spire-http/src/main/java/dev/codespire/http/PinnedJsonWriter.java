package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Separate write facade; context-provider read clients expose none of these methods. */
public final class PinnedJsonWriter {
    private final PinnedHttpTransport transport;
    public PinnedJsonWriter(PinnedJsonConfig config, ObjectMapper mapper, HttpFailures failures) {
        transport = new PinnedHttpTransport(config, mapper, failures);
    }
    public JsonNode post(String path, String json) { return transport.write("POST", path, json); }
    public JsonNode patch(String path, String json) { return transport.write("PATCH", path, json); }
    public JsonNode put(String path, String json) { return transport.write("PUT", path, json); }
    /** Jira transitions acknowledge success with exactly 204, not a JSON body. */
    public void postNoContent(String path, String json) { transport.writeNoContent("POST", path, json); }
}
