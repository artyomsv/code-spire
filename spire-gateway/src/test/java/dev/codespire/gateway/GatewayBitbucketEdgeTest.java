package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.RawWebhook;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy Bitbucket edge is wired purely by the presence of its webhook secret
 * — there is no provider flag. With a secret it verifies real signatures; without
 * one it rejects everything. (Which SCMs are reviewed is the UI registry, not config.)
 */
class GatewayBitbucketEdgeTest {

    private GatewayScmProducer producer(Optional<String> webhookSecret) {
        GatewayScmProducer producer = new GatewayScmProducer();
        producer.baseUrl = "https://api.bitbucket.org/2.0";
        producer.webhookSecret = webhookSecret;
        producer.mapper = new ObjectMapper();
        return producer;
    }

    @Test
    void withAWebhookSecretItVerifiesRealSignatures() throws Exception {
        String secret = "edge-secret";
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        var ingress = producer(Optional.of(secret)).ingress();
        assertTrue(ingress.verifySignature(new RawWebhook(
                Map.of("X-Hub-Signature", "sha256=" + hmac(secret, body)), body)));
    }

    @Test
    void withoutAWebhookSecretItRejectsEverything() {
        var ingress = producer(Optional.empty()).ingress();
        assertFalse(ingress.verifySignature(new RawWebhook(
                Map.of("X-Hub-Signature", "sha256=" + "ab".repeat(32)),
                "{}".getBytes(StandardCharsets.UTF_8))));
    }

    private static String hmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
