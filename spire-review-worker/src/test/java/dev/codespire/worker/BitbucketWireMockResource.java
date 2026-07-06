package dev.codespire.worker;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * WireMock "Bitbucket Cloud" + flips the worker to the REAL bitbucket-cloud
 * adapters, so the split test runs genuine adapter code with zero externals.
 */
public class BitbucketWireMockResource implements QuarkusTestResourceLifecycleManager {

    static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        stubBitbucket();
        // ADR-015: the worker no longer reads spire.scm.bitbucket.* — it builds the
        // client from each command's encrypted credential (the test packs one via
        // CryptoService pointing at this WireMock). Only the provider MODE stays here.
        return Map.of("spire.scm.provider", "bitbucket-cloud");
    }

    private void stubBitbucket() {
        String prPath = "/repositories/sandbox/demo-repo/pullrequests/42";
        server.stubFor(get(urlEqualTo(prPath))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "id": 42, "title": "E2E: add feature", "description": "e2e test PR",
                          "source": { "branch": {"name": "feature/e2e"}, "commit": {"hash": "e2ecafe00001"} },
                          "destination": { "branch": {"name": "main"} },
                          "author": { "account_id": "author-e2e", "nickname": "e2e", "display_name": "E2E" },
                          "links": { "html": {"href": "https://example.invalid/pr/42"} } }
                        """)));
        server.stubFor(get(urlEqualTo(prPath + "/diff"))
                .willReturn(aResponse().withHeader("Content-Type", "text/plain").withBody("""
                        diff --git a/src/Demo.java b/src/Demo.java
                        --- a/src/Demo.java
                        +++ b/src/Demo.java
                        @@ -1,3 +1,4 @@
                         class Demo {
                        +    int e2eAddedLine = 42;
                             void run() {}
                         }
                        """)));
        server.stubFor(post(urlEqualTo(prPath + "/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"id\": 991 }")));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
