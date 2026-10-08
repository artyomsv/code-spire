package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps subscription seats signed in (M3.5 part F, design §5.6 path (c), decided 2026-10-08).
 *
 * <p>An agent's copy of a sign-in has its refresh token emptied, so it cannot renew its access token, and
 * that token lives 10 days. Before this, a seat simply stopped working 10 days after sign-in while the
 * settings screen still said "Ready" (item #40, 2026-10-08). The orchestrator holds the whole file, so it
 * renews it here, with the vendor's own token endpoint and client — the request the vendor's CLI makes.
 * Which client and which address are configuration ({@code spire.seat-renewal.clients}), with how they
 * were measured written beside them.
 * The refresh token still never leaves the orchestrator, and nothing an agent wrote is ever read back.
 *
 * <p>One orchestrator renews at a time: the vendor rotates refresh tokens, and two renewals of one token
 * at once can end the whole sign-in.
 */
@ApplicationScoped
public class SeatRenewal {

    private static final Logger LOG = Logger.getLogger(SeatRenewal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Renew this long before the access token expires: a sweep that fails for a day still has room. */
    static final Duration RENEW_BEFORE = Duration.ofDays(3);

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long LOCK = 0x5EA7_2E4EL;

    @Inject HarnessCredentialPool pool;
    @Inject DataSource dataSource;
    @Inject SeatRenewalConfig config;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** What one renewal asks for: which client, at which address. */
    record Endpoint(String clientId, URI url) {}

    @Scheduled(every = "${spire.seat-renewal-interval:15m}", delayed = "30s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        config.clients().forEach((harness, client) ->
                renewDue(harness, new Endpoint(client.clientId(), URI.create(client.url())), Instant.now()));
    }

    /** @return how many seats of this harness were renewed */
    int renewDue(String harness, Endpoint endpoint, Instant now) {
        try (Connection c = dataSource.getConnection()) {
            if (!lock(c, "SELECT pg_try_advisory_lock(?)")) return 0;
            try {
                int renewed = 0;
                for (HarnessCredentialPool.StoredSeat seat : pool.seatsToRenew(harness)) {
                    if (isDue(seat, now) && renew(seat, endpoint, now)) renewed++;
                }
                return renewed;
            } finally {
                lock(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The seats of " + harness + " could not be renewed", e);
        }
    }

    private static boolean isDue(HarnessCredentialPool.StoredSeat seat, Instant now) {
        Optional<Instant> expires = SignInFiles.accessTokenExpiry(seat.file());
        if (expires.isEmpty()) LOG.debugf("seat %s has no readable expiry, so it is not renewed", seat.id());
        return expires.map(at -> at.isBefore(now.plus(RENEW_BEFORE))).orElse(false);
    }

    private boolean renew(HarnessCredentialPool.StoredSeat seat, Endpoint endpoint, Instant now) {
        Optional<String> refresh = SignInFiles.refreshTokenOf(seat.file());
        if (refresh.isEmpty()) {
            refused(seat, "no_refresh_token");
            return false;
        }
        HttpResponse<String> answer;
        try {
            answer = http.send(request(endpoint, refresh.orElseThrow()), HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            LOG.warnf("seat %s could not be renewed this time (%s); the next sweep tries again", seat.id(),
                    unreachable.getClass().getSimpleName());
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return stored(seat, answer, now);
    }

    private boolean stored(HarnessCredentialPool.StoredSeat seat, HttpResponse<String> answer, Instant now) {
        int status = answer.statusCode();
        if (status == 400 || status == 401) {
            refused(seat, errorCode(answer.body()));
            return false;
        }
        if (status != 200) {
            LOG.warnf("seat %s could not be renewed this time (HTTP %d); the next sweep tries again", seat.id(), status);
            return false;
        }
        String renewed;
        try {
            renewed = SignInFiles.renewed(seat.file(), answer.body(), now);
        } catch (IllegalStateException unreadable) {
            LOG.warnf("seat %s: the renewal answer had no access token; the next sweep tries again", seat.id());
            return false;
        }
        if (!pool.storeRenewal(seat, renewed)) {
            LOG.infof("seat %s changed while it was renewed; the newer sign-in stays", seat.id());
            return false;
        }
        LOG.infof("seat %s renewed", seat.id());
        return true;
    }

    /** The vendor will not renew this sign-in. Only a new sign-in brings the seat back. */
    private void refused(HarnessCredentialPool.StoredSeat seat, String code) {
        if (pool.markRejected(seat.id())) {
            LOG.errorf("seat %s could not be renewed (%s); it is out of use until it is signed in again", seat.id(), code);
        } else {
            LOG.debugf("seat %s is still refused (%s)", seat.id(), code);
        }
    }

    private HttpRequest request(Endpoint endpoint, String refreshToken) {
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("client_id", endpoint.clientId(), "grant_type", "refresh_token",
                    "refresh_token", refreshToken));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("A renewal request could not be written", impossible);
        }
        return HttpRequest.newBuilder(endpoint.url()).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    /** The vendor's error code, for the log. Only a short code: anything else in the answer stays unread. */
    static String errorCode(String answer) {
        try {
            com.fasterxml.jackson.databind.JsonNode tree = JSON.readTree(answer);
            String code = tree.path("error").isTextual() ? tree.path("error").asText() : tree.path("error").path("code").asText("");
            return code.matches("[a-z_]{1,64}") ? code : "unknown";
        } catch (JsonProcessingException unreadable) {
            return "unknown";
        }
    }

    private static boolean lock(Connection c, String statement) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(statement)) {
            ps.setLong(1, LOCK);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
