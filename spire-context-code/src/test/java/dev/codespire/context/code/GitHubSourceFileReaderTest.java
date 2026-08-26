package dev.codespire.context.code;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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

    // I3, rung-1 final review: the path is percent-encoded one segment at a time, not interpolated
    // raw — the slashes between segments stay literal, only each segment's own content is encoded.
    @Test
    void eachPathSegmentIsPercentEncodedSeparately() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/On%20Call.java"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.github.raw")
                        .withBody("class OnCall { }")));

        assertEquals("class OnCall { }", reader.read("acme/widgets", "src/On Call.java", "cafe1234"));
    }

    // Final rung-1 re-review: URLEncoder.encode implements application/x-www-form-urlencoded, which
    // encodes a space as `+` rather than `%20` — correct for a form body, wrong in a URL path, where
    // `+` is a literal plus. Verifying the actual request path directly (rather than only the file
    // content returned through a stub keyed on the correct path) pins the encoding itself, not just
    // one behaviour a wrong encoding happens to still produce.
    @Test
    void aSpaceInThePathIsRequestedAsPercentTwentyRatherThanAPlus() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/On%20Call.java"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.github.raw")
                        .withBody("class OnCall { }")));

        reader.read("acme/widgets", "src/On Call.java", "cafe1234");

        server.verify(getRequestedFor(urlPathEqualTo("/repos/acme/widgets/contents/src/On%20Call.java")));
    }
}
