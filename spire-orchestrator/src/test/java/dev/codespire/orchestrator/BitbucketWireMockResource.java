package dev.codespire.orchestrator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Boots a WireMock "Bitbucket Cloud" and flips the app to the REAL
 * bitbucket-cloud adapter pointed at it — the P1 exit criterion runs the
 * genuine adapter code path with zero external dependencies.
 */
public class BitbucketWireMockResource implements QuarkusTestResourceLifecycleManager {

    public static final String WEBHOOK_SECRET = "e2e-webhook-secret";
    public static final String BOT_ACCOUNT_ID = "bot-account-e2e";

    static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        stubBitbucket();
        return Map.of(
                "spire.scm.provider", "bitbucket-cloud",
                "spire.scm.bitbucket.base-url", "http://localhost:" + server.port(),
                "spire.scm.bitbucket.bot-username", "e2e-bot",
                "spire.scm.bitbucket.bot-app-password", "e2e-app-password",
                "spire.scm.bitbucket.webhook-secret", WEBHOOK_SECRET,
                "spire.scm.bitbucket.bot-account-id", BOT_ACCOUNT_ID);
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
