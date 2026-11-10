package dev.codespire.modelproxy;

import com.sun.net.httpserver.Headers;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The agent's request body, read whole and bounded before the vendor is contacted, so the vendor always sees a
 * declared length and a body the agent stalls or floods never holds a vendor connection.
 *
 * <p>A declared length over {@link Limits#maxRequestBodyBytes()} is refused before a byte is read; a chunked body is
 * counted as it arrives and refused at the first byte over. How long it may take to arrive is the JDK server's bound
 * ({@link ServerLimits}).
 */
final class RequestBody {

    private static final byte[] NONE = new byte[0];

    private RequestBody() {
    }

    /** The body, or empty when it is larger than {@code maxBytes}. A failed read is the agent's connection failing. */
    static Optional<byte[]> read(Headers headers, InputStream in, long maxBytes) throws IOException {
        String declared = headers.getFirst("Content-Length");
        if (declared != null) {
            long length = Long.parseLong(declared.strip());
            return length > maxBytes ? Optional.empty() : Optional.of(in.readNBytes((int) length));
        }
        if (!headers.containsKey("Transfer-Encoding")) {
            return Optional.of(NONE);
        }
        byte[] body = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
        return body.length > maxBytes ? Optional.empty() : Optional.of(body);
    }
}
