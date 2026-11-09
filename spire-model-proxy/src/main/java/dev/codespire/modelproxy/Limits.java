package dev.codespire.modelproxy;

import java.time.Duration;
import java.util.Objects;

/**
 * What one model proxy lets a request and a response cost. The agent shares the unit with this process and the vendor
 * is outside it, so neither may hold a socket, a thread or memory for longer or larger than these allow.
 *
 * <p>{@link #PRODUCTION} is what a run unit gets:
 *
 * <ul>
 *   <li><b>8 requests at once</b> ({@code maxConcurrentRequests}): Codex keeps one model stream and the odd model-list
 *       or account read beside it. A ninth is answered 503 at once, and capacity returns as soon as one ends.</li>
 *   <li><b>8 MiB of request body</b> ({@code maxRequestBodyBytes}): the body is read whole before the vendor is
 *       contacted, so it is bounded; a review's largest request (the whole conversation as JSON) stays far below it,
 *       and 8 of them fit the 192 MiB container's heap. A declared length over it is answered 413 before a byte is
 *       read; a chunked body is counted as it arrives and answered 413 at the first byte over.</li>
 *   <li><b>10 minutes for the vendor's response headers</b> ({@code responseHeadersTimeout}): a streamed answer sends
 *       its headers at once, and a slow non-streamed one still has ten minutes.</li>
 *   <li><b>10 minutes without a byte</b> ({@code idleRead}) of a response body: a model may think for minutes between
 *       events; the egress proxy's own idle timeout is the same 600 seconds.</li>
 *   <li><b>60 minutes for one response</b> ({@code totalResponse}): no review stream lasts that long, and the run's
 *       wall clock, which kills the whole unit, is the outer bound in any case.</li>
 * </ul>
 *
 * <p>The inbound side (headers and body arriving within 30 seconds, header count and size, open connections) is the
 * JDK server's to enforce; see {@link ServerLimits}.
 */
record Limits(int maxConcurrentRequests, long maxRequestBodyBytes, Duration responseHeadersTimeout,
              Duration idleRead, Duration totalResponse) {

    static final Limits PRODUCTION = new Limits(8, 8L * 1024 * 1024, Duration.ofMinutes(10),
            Duration.ofMinutes(10), Duration.ofMinutes(60));

    Limits {
        Objects.requireNonNull(responseHeadersTimeout, "responseHeadersTimeout");
        Objects.requireNonNull(idleRead, "idleRead");
        Objects.requireNonNull(totalResponse, "totalResponse");
        if (maxConcurrentRequests < 1 || maxRequestBodyBytes < 1) {
            throw new IllegalArgumentException("a model proxy needs room for one request of one byte");
        }
    }
}
