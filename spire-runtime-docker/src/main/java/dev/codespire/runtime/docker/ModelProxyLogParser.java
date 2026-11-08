package dev.codespire.runtime.docker;

import dev.codespire.runtime.ModelProxyReport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model proxy's log, read back as a {@link ModelProxyReport}.
 *
 * <p>The proxy writes one line per request, {@code spire-model <METHOD> <path> -> <status|refused|aborted>}, with the
 * method and path reduced to visible ASCII and at most 256 characters each (spire-model-proxy's {@code RequestLine}).
 * The path is still the agent's choice, so the line is matched whole and strictly, anything else in the log (the
 * proxy's own startup refusal, a JVM message) is ignored, and what reading the log costs is bounded like the egress
 * proxy's ({@link ProxyLogParser}): {@link #MAX_LOG_CHARS} in all, {@link #MAX_LINE_CHARS} a line, and
 * {@link #MAX_REFUSED_PATHS} distinct refused paths.
 */
final class ModelProxyLogParser {

    static final int MAX_LOG_CHARS = ProxyLogParser.MAX_LOG_CHARS;

    static final int MAX_LINE_CHARS = ProxyLogParser.MAX_LINE_CHARS;

    static final int MAX_REFUSED_PATHS = 256;

    private static final Pattern LINE = Pattern.compile("^spire-model (\\S{1,256}) (\\S{1,256}) -> (\\d{3}|refused|aborted)$");

    private static final String UNAUTHORIZED = "401";

    private static final String REFUSED = "refused";

    private ModelProxyLogParser() {
    }

    static ModelProxyReport parse(List<String> lines) {
        int requests = 0;
        int rejected = 0;
        Set<String> refused = new LinkedHashSet<>();
        long read = 0;
        for (String line : lines) {
            read += line.length() + 1L;
            if (read > MAX_LOG_CHARS) break;
            if (line.length() > MAX_LINE_CHARS) continue;
            Matcher request = LINE.matcher(line);
            if (!request.matches()) continue;
            requests++;
            String path = request.group(2);
            String outcome = request.group(3);
            if ((UNAUTHORIZED.equals(outcome) || "403".equals(outcome)) && ModelProxyReport.MODEL_CALL_PATHS.contains(path)) {
                rejected++;
            } else if (REFUSED.equals(outcome) && refused.size() < MAX_REFUSED_PATHS) {
                refused.add(path);
            }
        }
        return new ModelProxyReport(requests, rejected, List.copyOf(refused));
    }
}
