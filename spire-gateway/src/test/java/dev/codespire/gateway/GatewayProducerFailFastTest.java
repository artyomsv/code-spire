package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-001 fail-fast: the gateway's ingress producer refuses misconfiguration, naming the key. */
class GatewayProducerFailFastTest {

    private GatewayScmProducer producer(String provider, Optional<String> webhookSecret) {
        GatewayScmProducer producer = new GatewayScmProducer();
        producer.provider = provider;
        producer.baseUrl = "https://api.bitbucket.org/2.0";
        producer.webhookSecret = webhookSecret;
        producer.botAccountId = Optional.of("acc-1");
        producer.mapper = new ObjectMapper();
        return producer;
    }

    @Test
    void bitbucketIngressWithoutWebhookSecretFailsNamingTheKey() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> producer("bitbucket-cloud", Optional.empty()).ingress());
        assertTrue(thrown.getMessage().contains("spire.scm.bitbucket.webhook-secret"));
    }

    @Test
    void unknownProviderFails() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> producer("gitlab", Optional.of("s")).ingress());
        assertTrue(thrown.getMessage().contains("Unknown spire.scm.provider"));
    }

    @Test
    void stubIngressRejectsEverythingWithoutCredentials() {
        GatewayScmProducer stub = new GatewayScmProducer();
        stub.provider = "stub";
        stub.webhookSecret = Optional.empty();
        stub.botAccountId = Optional.empty();
        var ingress = stub.ingress();
        assertNotNull(ingress);
        assertTrue(!ingress.verifySignature(new dev.codespire.contract.port.RawWebhook(
                java.util.Map.of(), new byte[0])));
    }
}
