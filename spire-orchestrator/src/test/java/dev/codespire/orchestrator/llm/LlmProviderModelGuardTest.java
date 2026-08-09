package dev.codespire.orchestrator.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The Settings dropdown is a courtesy; this is the control. A provider naming a model that is not in
 * the catalog cannot be priced, so every call it makes would land in the ledger as UNKNOWN — the exact
 * state the accounting rework exists to make impossible to configure.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmProviderModelGuardTest {

    @Test
    void aProviderNamingAnUncataloguedModelIsRejected() {
        // The guard runs before the key is ever validated against the provider, so an unreachable
        // https://*.invalid baseUrl is fine here — the request never gets that far.
        given().contentType(ContentType.JSON)
                .body("""
                      {"name":"TEST provider","type":"openai","baseUrl":"https://api.example.invalid",
                       "apiKey":"TEST-KEY","model":"TEST-NOT-IN-CATALOG"}
                      """)
                .when().post("/api/llm-providers")
                .then().statusCode(400).body(containsString("catalog"));
    }

    @Test
    void aProviderNamingACataloguedPriceableModelIsAccepted() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-GUARD-MODEL","label":"TEST guard",
                       "pricingMode":"METERED","rates":{"INPUT":200000,"OUTPUT":400000}}
                      """)
                .when().post("/api/llm-models").then().statusCode(201);

        // Unlike the rejection case above, this request must clear the guard and reach the real
        // key-validation ping — so the baseUrl needs an endpoint that actually answers, matching
        // LlmProviderResourceTest's WireMock stand-in for the provider's /models endpoint.
        WireMockServer llm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        llm.start();
        try {
            llm.stubFor(get(urlEqualTo("/models"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{ \"data\": [] }")));

            given().contentType(ContentType.JSON)
                    .body(Map.of("name", "TEST provider ok", "type", "openai",
                            "baseUrl", llm.baseUrl(), "apiKey", "TEST-KEY", "model", "TEST-GUARD-MODEL"))
                    .when().post("/api/llm-providers")
                    .then().statusCode(201);
        } finally {
            llm.stop();
        }
    }
}
