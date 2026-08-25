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

class GitLabSourceFileReaderTest {

    private WireMockServer server;
    private GitLabSourceFileReader reader;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        reader = new GitLabSourceFileReader(new CodeContextConfig(
                "http://localhost:" + server.port(), "bearer", "CANARY-TOKEN", Set.of()));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void readsAFileAtTheGivenCommit() {
        server.stubFor(get(urlPathEqualTo(
                "/api/v4/projects/acme%2Fwidgets/repository/files/src%2FAlpha.java/raw"))
                .willReturn(aResponse().withStatus(200).withBody("class Alpha { }")));

        assertEquals("class Alpha { }", reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
    }

    @Test
    void anAbsentFileIsNullRatherThanAnError() {
        server.stubFor(get(urlPathEqualTo(
                "/api/v4/projects/acme%2Fwidgets/repository/files/src%2FMissing.java/raw"))
                .willReturn(aResponse().withStatus(404)));

        assertNull(reader.read("acme/widgets", "src/Missing.java", "cafe1234"));
    }

    @Test
    void anUnauthorizedResponseIsRaisedSoTheCredentialCanBeMarkedRejected() {
        server.stubFor(get(urlPathEqualTo(
                "/api/v4/projects/acme%2Fwidgets/repository/files/src%2FAlpha.java/raw"))
                .willReturn(aResponse().withStatus(401)));

        CodeContextApiException e = assertThrows(CodeContextApiException.class,
                () -> reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
        assertEquals(401, e.status());
    }

    @Test
    void theProjectPathIsFullyUrlEncodedIncludingSlashes() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/acme%2Fwidgets/repository/files/src%2FAlpha.java/raw"))
                .willReturn(aResponse().withStatus(200).withBody("class Alpha { }")));

        assertEquals("class Alpha { }", reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
    }
}
