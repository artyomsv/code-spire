package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A seat is renewed by the orchestrator before its access token runs out, because the agent's copy cannot
 * renew itself (item #40, 2026-10-08). The vendor's token endpoint is a local WireMock; every token is a
 * placeholder.
 */
@QuarkusTest
class SeatRenewalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HARNESS = "TEST-renewal-harness";
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    static WireMockServer vendor;

    @Inject SeatRenewal renewal;
    @Inject HarnessCredentialPool pool;
    @Inject DataSource dataSource;
    private final List<UUID> seats = new ArrayList<>();

    @BeforeAll static void start() { vendor = new WireMockServer(WireMockConfiguration.options().dynamicPort()); vendor.start(); }
    @AfterAll static void stop() { vendor.stop(); }
    @BeforeEach void reset() { vendor.resetAll(); }
    @AfterEach void switchSeatsOff() { seats.forEach(pool::remove); }

    private SeatRenewal.Endpoint endpoint() {
        return new SeatRenewal.Endpoint("TEST-client", URI.create(vendor.baseUrl() + "/oauth/token"));
    }

    private UUID seatExpiringIn(Duration left) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID id = pool.addSubscription(c, "TEST-renewal-" + UUID.randomUUID(), HARNESS,
                    "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"" + SignInFilesTest.jwt(NOW.plus(left))
                            + "\",\"id_token\":\"TEST-id\",\"refresh_token\":\"TEST-refresh-1\",\"account_id\":\"TEST-account-"
                            + UUID.randomUUID() + "\"},\"last_refresh\":\"1970-01-01T00:00:00Z\"}");
            seats.add(id);
            return id;
        }
    }

    private JsonNode storedTokens(UUID seat) throws Exception {
        return JSON.readTree(pool.seatsToRenew(HARNESS).stream().filter(s -> s.id().equals(seat)).findFirst().orElseThrow().file())
                .path("tokens");
    }

    private HarnessCredentialPool.MemberView view(UUID seat) {
        return pool.list().stream().filter(member -> member.id().equals(seat)).findFirst().orElseThrow();
    }

    private void vendorAnswers(int status, String body) {
        vendor.stubFor(post("/oauth/token").willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void aSeatCloseToExpiryIsRenewedWithItsStoredRefreshToken() throws Exception {
        UUID seat = seatExpiringIn(Duration.ofDays(1));
        String fresh = SignInFilesTest.jwt(NOW.plus(Duration.ofDays(10)));
        vendorAnswers(200, "{\"access_token\":\"" + fresh + "\",\"id_token\":\"TEST-id-2\",\"refresh_token\":\"TEST-refresh-2\"}");

        assertEquals(1, renewal.renewDue(HARNESS, endpoint(), NOW));

        vendor.verify(1, postRequestedFor(urlEqualTo("/oauth/token"))
                .withRequestBody(matchingJsonPath("$.refresh_token", equalTo("TEST-refresh-1")))
                .withRequestBody(matchingJsonPath("$.client_id", equalTo("TEST-client")))
                .withRequestBody(matchingJsonPath("$.grant_type", equalTo("refresh_token"))));
        JsonNode tokens = storedTokens(seat);
        assertEquals(fresh, tokens.path("access_token").asText());
        assertEquals("TEST-refresh-2", tokens.path("refresh_token").asText());
    }

    /** The case of item #40: the access token had already expired, and the refresh token still worked. */
    @Test
    void anAlreadyExpiredSeatIsRenewedAndItsRefusalCleared() throws Exception {
        UUID seat = seatExpiringIn(Duration.ofDays(-1));
        pool.markRejected(seat);
        vendorAnswers(200, "{\"access_token\":\"" + SignInFilesTest.jwt(NOW.plus(Duration.ofDays(10))) + "\"}");

        assertEquals(1, renewal.renewDue(HARNESS, endpoint(), NOW));

        assertNull(view(seat).rejectedAt());
        assertEquals("TEST-refresh-1", storedTokens(seat).path("refresh_token").asText());
    }

    @Test
    void aSeatWithTimeLeftIsNotRenewed() throws Exception {
        seatExpiringIn(Duration.ofDays(9));
        vendorAnswers(200, "{\"access_token\":\"TEST-unused\"}");

        assertEquals(0, renewal.renewDue(HARNESS, endpoint(), NOW));

        vendor.verify(0, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    /** The screen must stop saying "Ready" for a seat nothing can renew. */
    @Test
    void aRefusedRenewalTakesTheSeatOutOfUse() throws Exception {
        UUID seat = seatExpiringIn(Duration.ofDays(1));
        String before = storedTokens(seat).toString();
        vendorAnswers(401, "{\"error\":{\"code\":\"refresh_token_expired\",\"message\":\"TEST\"}}");

        assertEquals(0, renewal.renewDue(HARNESS, endpoint(), NOW));

        assertNotNull(view(seat).rejectedAt());
        assertEquals(before, storedTokens(seat).toString());
        assertTrue(pool.selectSubscription(HARNESS).isEmpty(), "a refused seat must not be handed to a build");
    }

    /** OAuth's invalid_grant is a 400, not a 401: it is a refusal too, or the seat keeps saying Ready (review of PR #184). */
    @Test
    void aRenewalRefusedWithA400TakesTheSeatOutOfUse() throws Exception {
        UUID seat = seatExpiringIn(Duration.ofDays(1));
        vendorAnswers(400, "{\"error\":\"invalid_grant\"}");

        assertEquals(0, renewal.renewDue(HARNESS, endpoint(), NOW));

        assertNotNull(view(seat).rejectedAt());
    }

    /** A seat stored with no refresh token can never be renewed, so it is refused without asking the vendor. */
    @Test
    void aSeatWithNoRefreshTokenIsRefusedWithoutAskingTheVendor() throws Exception {
        UUID seat;
        try (Connection c = dataSource.getConnection()) {
            seat = pool.addSubscription(c, "TEST-renewal-" + UUID.randomUUID(), HARNESS,
                    "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"" + SignInFilesTest.jwt(NOW.plus(Duration.ofDays(1)))
                            + "\",\"refresh_token\":\"\",\"account_id\":\"TEST-account-" + UUID.randomUUID() + "\"}}");
            seats.add(seat);
        }
        vendorAnswers(200, "{\"access_token\":\"TEST-unused\"}");

        assertEquals(0, renewal.renewDue(HARNESS, endpoint(), NOW));

        assertNotNull(view(seat).rejectedAt());
        vendor.verify(0, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    /** A vendor outage is not a refusal: the seat stays in use and the next sweep tries again. */
    @Test
    void aVendorOutageLeavesTheSeatAsItWas() throws Exception {
        UUID seat = seatExpiringIn(Duration.ofDays(1));
        String before = storedTokens(seat).toString();
        vendorAnswers(503, "{}");

        assertEquals(0, renewal.renewDue(HARNESS, endpoint(), NOW));

        assertNull(view(seat).rejectedAt());
        assertEquals(before, storedTokens(seat).toString());
    }

    /** An operator who signs in again while a renewal is in flight keeps the newer sign-in. */
    @Test
    void aRenewalOfASeatSignedInAgainMeanwhileIsDropped() throws Exception {
        UUID seat = seatExpiringIn(Duration.ofDays(1));
        HarnessCredentialPool.StoredSeat read = pool.seatsToRenew(HARNESS).stream().filter(s -> s.id().equals(seat))
                .findFirst().orElseThrow();
        try (Connection c = dataSource.getConnection()) {
            pool.replaceSubscription(c, seat, read.file().replace("TEST-refresh-1", "TEST-refresh-signed-in-again"));
        }

        assertFalse(pool.storeRenewal(read, read.file().replace("TEST-refresh-1", "TEST-refresh-renewed")));

        assertEquals("TEST-refresh-signed-in-again", storedTokens(seat).path("refresh_token").asText());
    }

    @Test
    void onlyAShortVendorCodeReachesTheLog() {
        assertEquals("refresh_token_reused", SeatRenewal.errorCode("{\"error\":{\"code\":\"refresh_token_reused\"}}"));
        assertEquals("invalid_grant", SeatRenewal.errorCode("{\"error\":\"invalid_grant\"}"));
        assertEquals("unknown", SeatRenewal.errorCode("{\"error\":{\"code\":\"TEST-secret-looking value\"}}"));
        assertEquals("unknown", SeatRenewal.errorCode("TEST-not-json"));
    }
}
