package dev.codespire.context.code;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubSourceFileReaderTest {

    private WireMockServer server;
    private GitHubSourceFileReader reader;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        reader = new GitHubSourceFileReader(new CodeContextConfig(
                "http://localhost:" + server.port(), "bearer", "CANARY-TOKEN", Set.of()));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void readsAFileAtTheGivenCommit() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/Alpha.java"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.github.raw")
                        .withBody("class Alpha { }")));

        assertEquals("class Alpha { }", reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
    }

    @Test
    void anAbsentFileIsNullRatherThanAnError() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/Missing.java"))
                .willReturn(aResponse().withStatus(404)));

        assertNull(reader.read("acme/widgets", "src/Missing.java", "cafe1234"));
    }

    @Test
    void anUnauthorizedResponseIsRaisedSoTheCredentialCanBeMarkedRejected() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/Alpha.java"))
                .willReturn(aResponse().withStatus(401)));

        CodeContextApiException e = assertThrows(CodeContextApiException.class,
                () -> reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
        assertEquals(401, e.status());
    }
}
