package dev.codespire.contract.port;

import java.util.Map;

/** An inbound webhook exactly as received — headers + raw body for signature verification. */
public record RawWebhook(Map<String, String> headers, byte[] body) {
}
