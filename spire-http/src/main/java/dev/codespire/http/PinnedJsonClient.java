package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Read-only JSON over HTTP against one pinned API host — the shared transport every context adapter
 * builds on.
 *
 * <p>Redirects are followed MANUALLY with host pinning: the bot's Authorization header is only ever
 * sent to the configured API host, never to a cross-host redirect target, and a cross-host hop into
 * loopback/link-local/private space is refused outright (SSRF guard). The credential is never logged.
 *
 * <p>This class exists so that guard has ONE home. It previously stood as an identical copy inside
 * each adapter, which meant a fix to it had to land in every copy and nothing failed if it landed in
 * all but one.
 */
public class PinnedJsonClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_REDIRECTS = 3;
    /** Not a real HTTP status — the status the redirect-loop guard reports through the failure factory. */
    private static final int TOO_MANY_REDIRECTS = 310;

    /**
     * Hard byte cap on a {@link #getRaw} response body, enforced while the body is still arriving.
     *
     * <p>A raw fetch returns whatever bytes the remote path holds, and nothing upstream of this class
     * knows that size in advance: a repository can legitimately hold a committed 25 MB minified
     * bundle, and one changed character in it makes the path a candidate to fetch. Without a cap the
     * whole thing lands in a Java {@code String} (roughly twice its byte size in heap) inside a
     * process that serves every repository in the deployment, so a one-line pull request is enough to
     * exhaust it. The cap is generous for the thing this method is actually for — a source file a
     * reviewer would read — and small enough that a handful of concurrent fetches cannot matter.
     *
     * <p>Deliberately NOT applied to {@link #getJson}: those are the providers' own bounded API
     * envelopes, and silently truncating one would turn a size problem into a parse error.
     */
    public static final int MAX_RAW_BYTES = 1_048_576;

    /** {@link #execute}'s "read the whole body" sentinel — the JSON path's behaviour, unchanged. */
    private static final int UNBOUNDED = -1;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String apiName;
    private final String authorization;
    private final Map<String, String> headers;
    private final String rejectedCredentialHint;
    private final HttpFailures failures;

    public PinnedJsonClient(PinnedJsonConfig config, ObjectMapper mapper, HttpFailures failures) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // manual, host-pinned
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = mapper;
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.apiName = config.apiName();
        this.authorization = config.authorization();
        this.headers = config.headers();
        this.rejectedCredentialHint = config.rejectedCredentialHint();
        this.failures = failures;
    }

    public JsonNode getJson(String path) {
        return parse(send("GET", path, true));
    }

    /**
     * GET a path whose 2xx body IS the payload itself — a raw source file, not a JSON envelope.
     *
     * <p>Same host-pinned, redirect-following, SSRF-guarded transport as {@link #getJson}, minus the
     * JSON-shape check: that check exists to catch a request silently redirected to an HTML sign-in
     * page, and it works by asserting a 2xx body parses as JSON. A legitimate raw-file response is
     * ordinary source text, which routinely fails that assertion on a successful call, so this method
     * skips it and returns the body verbatim. Non-2xx statuses (404, 401, ...) are still classified
     * through the adapter's own {@link HttpFailures}, exactly as {@link #getJson} does.
     *
     * @return the body, or {@code null} when it exceeds {@link #MAX_RAW_BYTES} — nothing over the cap
     *     is ever held: a body that declares an over-cap length is discarded outright, and one that
     *     does not declare a length stops being buffered the moment it crosses. Reported as
     *     <em>absent</em> rather than as a failure on purpose: a file too large to read is, to every
     *     caller of this method, the same non-answer as a file that is not there, and raising instead
     *     would let one oversized path turn into an error the caller has no better response to.
     */
    public String getRaw(String path) {
        return send("GET", path, false);
    }

    private String send(String method, String path, boolean requireJsonShape) {
        int maxBytes = requireJsonShape ? UNBOUNDED : MAX_RAW_BYTES;
        URI target = URI.create(baseUri + path);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = execute(method, path, target, maxBytes);
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> failures.create(status, method, path, null));
                target = redirectTarget(target, location, status, method, path);
                requireSafeRedirectTarget(target, status, method, path);
                continue;
            }
            if (status / 100 != 2) {
                throw failures.create(status, method, path, bodySnippet(response.body()));
            }
            String body = response.body();
            if (!requireJsonShape) {
                return body; // null when the bounded subscriber aborted past MAX_RAW_BYTES
            }
            // A 2xx must be JSON. A non-JSON 2xx (an HTML sign-in page) means the request was
            // redirected to authentication — the token was not accepted. Surface it clearly here
            // instead of as a raw JSON parse error deep in the caller.
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!looksLikeJson(contentType, body)) {
                throw failures.create(status, method, path,
                        "expected JSON but received " + describeType(contentType)
                                + " — the request was redirected to a sign-in page, so the token was not "
                                + "accepted. " + rejectedCredentialHint + " Body starts: " + bodySnippet(body));
            }
            return body;
        }
        throw failures.create(TOO_MANY_REDIRECTS, method, path, null);
    }

    private static boolean looksLikeJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            return true;
        }
        String trimmed = body == null ? "" : body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String describeType(String contentType) {
        return contentType == null || contentType.isBlank() ? "a non-JSON response" : contentType;
    }

    /**
     * Resolve a {@code Location} header against the current target.
     *
     * <p>A malformed value ({@code Location: http://}) makes {@link URI#resolve} throw, which would
     * otherwise escape this transport as an unchecked exception, past callers that only expect the
     * adapter's own type. Refusing it here keeps every redirect failure one shape.
     */
    private URI redirectTarget(URI current, String location, int status, String method, String path) {
        try {
            return current.resolve(location);
        } catch (IllegalArgumentException e) {
            throw failures.create(status, method, path, "unparseable redirect target refused");
        }
    }

    /**
     * SSRF guard on redirect hops: a cross-host Location must not point into loopback/link-local/
     * private/unique-local address space. Same-host targets skip the check — the base host is
     * operator config, not attacker data, and dev/test legitimately run against WireMock on localhost.
     */
    private void requireSafeRedirectTarget(URI target, int status, String method, String path) {
        String host = target.getHost();
        if (host == null) {
            throw failures.create(status, method, path, "redirect without a host refused");
        }
        if (host.equalsIgnoreCase(baseUri.getHost())) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    throw failures.create(status, method, path,
                            "redirect to non-public address refused: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new UncheckedIOException(apiName + " " + method + " " + path
                    + " redirect target did not resolve", e);
        }
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        return raw.length == 16 && (raw[0] & 0xFE) == 0xFC; // IPv6 unique-local fc00::/7
    }

    /** Truncated response-body excerpt for error messages — no headers, so no secrets. */
    private static String bodySnippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String cleaned = body.replaceAll("\\s+", " ").strip();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500) + "...";
    }

    private HttpResponse<String> execute(String method, String path, URI target, int maxBytes) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(TIMEOUT);
        headers.forEach(builder::header);
        if (sameOrigin(target)) {
            builder.header("Authorization", authorization); // pinned to the API host only
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        try {
            return http.send(builder.build(), bodyHandler(maxBytes));
        } catch (IOException e) {
            throw new UncheckedIOException(apiName + " " + method + " " + path + " I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + apiName, e);
        }
    }

    private boolean sameOrigin(URI target) {
        return baseUri.getScheme().equalsIgnoreCase(target.getScheme())
                && baseUri.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(baseUri) == effectivePort(target);
    }

    /** -1 (no explicit port) normalizes to the scheme default, so ":443" still matches. */
    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable " + apiName + " response", e);
        }
    }

    /**
     * {@code maxBytes <= 0} reads the whole body, as {@link #getJson} always has.
     *
     * <p>A declared {@code Content-Length} over the cap short-circuits: the body is discarded without
     * ever being assembled, which is both cheaper and the common case — the raw-content APIs this
     * serves all declare a length for a file. A chunked response carries no length to read, so it
     * falls to {@link BoundedStringSubscriber}, which decides as the bytes arrive.
     */
    private static HttpResponse.BodyHandler<String> bodyHandler(int maxBytes) {
        if (maxBytes <= 0) {
            return HttpResponse.BodyHandlers.ofString();
        }
        return responseInfo -> {
            if (responseInfo.headers().firstValueAsLong("content-length").orElse(-1L) > maxBytes) {
                return HttpResponse.BodySubscribers.<String>replacing(null);
            }
            return new BoundedStringSubscriber(maxBytes);
        };
    }

    /**
     * Buffers a response body up to {@code maxBytes} and <b>stops buffering</b> past that, completing
     * with {@code null} — the sentinel {@link #getRaw} turns into "absent". Bytes over the cap are
     * read off the connection and dropped, never held, so heap is bounded by {@code maxBytes} whatever
     * the remote sends.
     *
     * <p>It reads them rather than <b>cancelling</b> the subscription on purpose, having tried that
     * first: the JDK client reports a cancelled body subscription as {@code IOException: Stream
     * cancelled} out of {@code HttpClient.send}, so aborting turns a bounded read into a transport
     * failure the caller would have to distinguish from a genuine one — trading a heap problem for a
     * correctness problem. Draining costs bandwidth on a response the {@code Content-Length}
     * short-circuit above already handles whenever the remote declares its size, and the request
     * timeout bounds the rest.
     *
     * <p>Hand-rolled rather than composed from {@code BodySubscribers}: every buffering subscriber the
     * JDK ships assembles the whole body, which is precisely what is being bounded here, and
     * {@code BodySubscribers.mapping} over an input-stream subscriber blocks the client's own thread
     * to do the reading.
     */
    private static final class BoundedStringSubscriber implements HttpResponse.BodySubscriber<String> {

        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private boolean overCap;

        BoundedStringSubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (overCap) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + item.remaining() > maxBytes) {
                    overCap = true;
                    buffer.reset(); // nothing about an over-cap body is worth keeping
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(overCap ? null : buffer.toString(StandardCharsets.UTF_8));
        }
    }
}
