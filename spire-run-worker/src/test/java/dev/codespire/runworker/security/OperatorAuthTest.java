package dev.codespire.runworker.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run worker's HTTP surface is deny-by-default, with health the one public path.
 *
 * <p>There is no operator endpoint under {@code /rw} yet, and the test is written so that stays
 * irrelevant: the permission policy answers 401 before routing, so an endpoint added tomorrow is
 * refused to an anonymous caller whether or not anyone remembered to annotate it. Plain JDK HTTP,
 * because that is what the module's test classpath has.
 */
@QuarkusTest
class OperatorAuthTest {

    @TestHTTPResource("/q/health")
    URL health;

    @TestHTTPResource("/rw/anything")
    URL operatorPrefix;

    @TestHTTPResource("/anything")
    URL outsideEveryPrefix;

    private static int status(URL url) throws IOException, InterruptedException {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url.toString())).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    @Test
    void healthStaysPublic() throws Exception {
        assertEquals(200, status(health));
    }

    @Test
    void aPathOutsideEveryPrefixIsDeniedNotPermitted() throws Exception {
        // Quarkus permits what matches no permission entry. "Deny by default" was asserted by a
        // javadoc and probed under /rw only; the catch-all deny is what makes it true everywhere.
        int status = status(outsideEveryPrefix);
        assertTrue(status == 401 || status == 403, "expected a refusal outside the prefix, got " + status);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedUnderTheOperatorPrefix() throws Exception {
        // 401 with a challenge mechanism, 403 without one (the test profile disables OIDC at build
        // time, so no mechanism can challenge). Either is the refusal; what must never come back is
        // a 200 or the 404 of a route that was reached.
        int status = status(operatorPrefix);
        assertTrue(status == 401 || status == 403, "expected a refusal, got " + status);
    }

    @Test
    void anUnauthenticatedSurfaceIsPermittedOnlyInDevAndTest() {
        assertTrue(OperatorAuthorization.isPermitted(false, LaunchMode.DEVELOPMENT));
        assertTrue(OperatorAuthorization.isPermitted(false, LaunchMode.TEST));
        assertFalse(OperatorAuthorization.isPermitted(false, LaunchMode.NORMAL));
        assertTrue(OperatorAuthorization.isPermitted(true, LaunchMode.NORMAL));
    }

    @Test
    void forwardingNeedsATrustedProxyOfNonZeroWidth() {
        assertTrue(OperatorAuthorization.isForwardingSafe(false, ""));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, ""));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "0.0.0.0/0"));
        assertFalse(OperatorAuthorization.isForwardingSafe(true, "10.0.0.0/8, ::/0"));
        assertTrue(OperatorAuthorization.isForwardingSafe(true, "10.0.0.0/8"));
    }
}
