package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkSourceScannerTest extends WorkFixture {
    FutureTask<Boolean> delayedEmptyPage() {
        String candidates = "/repos/" + scope + "/issues?state=open&sort=created&direction=asc&per_page=100&page=1";
        forge.stubFor(get(urlEqualTo(candidates)).willReturn(okJson("[]").withFixedDelay(3000)
                .withHeader("Link", "<" + forge.baseUrl() + candidates.replace("&page=1", "&page=2") + ">; rel=\"next\"")));
        FutureTask<Boolean> scan = new FutureTask<>(() -> scanner.scan(source));
        Thread.ofVirtual().start(scan);
        await().atMost(Duration.ofSeconds(5)).until(() -> !forge.findAll(getRequestedFor(urlEqualTo(candidates))).isEmpty());
        return scan;
    }

    @Test void anEmptyPageCannotCommitAfterSourceAuthorityChanges() throws Exception {
        FutureTask<Boolean> scan = delayedEmptyPage();
        execute("UPDATE work_source SET revision=revision+1 WHERE id=?", source);
        assertFalse(scan.get(10, TimeUnit.SECONDS));
        assertNull(sources.get(source).orElseThrow().cursor());
    }

    @Test void anEmptyPageCannotOverwriteAnotherCommittedCursor() throws Exception {
        FutureTask<Boolean> scan = delayedEmptyPage();
        execute("UPDATE work_source SET scan_cursor='2' WHERE id=?", source);
        assertFalse(scan.get(10, TimeUnit.SECONDS));
        assertEquals("2", sources.get(source).orElseThrow().cursor());
    }

    @Test void anEmptyPageCannotCommitAfterPolicyChanges() throws Exception {
        FutureTask<Boolean> scan = delayedEmptyPage();
        execute("UPDATE work_repository_policy SET revision=revision+1 WHERE repository_id=?", repository);
        assertFalse(scan.get(10, TimeUnit.SECONDS));
        assertNull(sources.get(source).orElseThrow().cursor());
    }

    @Test void unavailableCandidatesPreserveTheCursorAndRecordHealth() {
        forge.stubFor(get(urlPathEqualTo("/repos/" + scope + "/issues")).willReturn(aResponse().withStatus(403)));
        assertFalse(scanner.scan(source));
        assertNull(sources.get(source).orElseThrow().cursor());
        assertEquals("scan_unavailable", sources.get(source).orElseThrow().health());
    }
}
