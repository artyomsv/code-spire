package dev.codespire.runtime.docker;

import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.EgressPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The egress proxy's log, read back as the hosts the agent asked for.
 *
 * <p>The lines are tinyproxy 1.11's, taken from its source: {@code Request (file descriptor N): <request line>} at
 * LOG_CONN, which {@code LogLevel Connect} writes, and {@code Proxying refused on filtered domain "<host>"} at
 * LOG_NOTICE. Its refusal of a CONNECT to a port outside {@code ConnectPort} is at LOG_INFO, below that level, so the
 * port rule is applied here instead.
 *
 * <p>The log is agent-controlled text: every line names a host the agent chose. So it is parsed strictly. A request
 * line whose target does not parse as a host, or a host that is not a plain name or address, is not reported at all
 * rather than reported mangled; a URL's user information never leaves here; and the answer stops at
 * {@link #MAX_ATTEMPTS} distinct attempts.
 */
final class ProxyLogParser {

    /** These bound what reading the log costs the worker, whatever the agent wrote. */
    static final int MAX_LOG_CHARS = 8 * 1024 * 1024;

    static final int MAX_LINE_CHARS = 4096;

    static final int MAX_ATTEMPTS = 256;

    private static final Pattern REQUEST =
            Pattern.compile("Request \\(file descriptor \\d+\\): ([A-Z]{3,10}) (\\S{1,2048}) HTTP/\\d(?:\\.\\d)?$");

    private static final Pattern REFUSED = Pattern.compile("Proxying refused on filtered (?:domain|url) \"([^\"]{1,2048})\"");

    private static final Pattern ABSOLUTE_URL = Pattern.compile("(?i)^(https?)://([^/?#]+)");

    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?|\\[[0-9a-f:.]{2,45}\\]");

    private ProxyLogParser() {
    }

    /** Every host and port the log shows a request for, allowed unless the proxy refused that host or its port. */
    static List<EgressAttempt> parse(List<String> lines) {
        // Keyed by host and port; the value says whether the port alone already refuses it.
        Map<Map.Entry<String, Integer>, Boolean> requested = new LinkedHashMap<>();
        Set<String> refused = new LinkedHashSet<>();
        for (String line : lines) {
            if (line.length() > MAX_LINE_CHARS) continue;
            Matcher request = REQUEST.matcher(line);
            if (request.find()) {
                boolean connect = "CONNECT".equals(request.group(1));
                target(request.group(1), request.group(2)).ifPresent(hostPort -> {
                    if (requested.size() < MAX_ATTEMPTS || requested.containsKey(hostPort)) {
                        boolean portRefused = connect && !EgressPolicy.PORTS.contains(hostPort.getValue());
                        requested.merge(hostPort, portRefused, Boolean::logicalOr);
                    }
                });
                continue;
            }
            Matcher refusal = REFUSED.matcher(line);
            if (refusal.find()) {
                hostOf(refusal.group(1)).ifPresent(refused::add);
            }
        }
        List<EgressAttempt> attempts = new ArrayList<>();
        requested.forEach((hostPort, portRefused) -> attempts.add(new EgressAttempt(hostPort.getKey(), hostPort.getValue(),
                !portRefused && !refused.contains(hostPort.getKey()))));
        return List.copyOf(attempts);
    }

    /** The host and port a request line names: {@code host:port} for CONNECT, an absolute URL otherwise. */
    private static Optional<Map.Entry<String, Integer>> target(String method, String target) {
        String authority;
        int defaultPort;
        if ("CONNECT".equals(method)) {
            authority = target;
            defaultPort = 443;
        } else {
            Matcher url = ABSOLUTE_URL.matcher(target);
            if (!url.find()) return Optional.empty();
            authority = url.group(2);
            defaultPort = "https".equalsIgnoreCase(url.group(1)) ? 443 : 80;
        }
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        int colon = authority.lastIndexOf(':');
        boolean hasPort = colon > authority.lastIndexOf(']');
        String host = hasPort ? authority.substring(0, colon) : authority;
        int port = defaultPort;
        if (hasPort) {
            String digits = authority.substring(colon + 1);
            if (!digits.matches("\\d{1,5}")) return Optional.empty();
            port = Integer.parseInt(digits);
            if (port < 1 || port > 65535) return Optional.empty();
        }
        int resolvedPort = port;
        return hostOf(host).map(name -> Map.entry(name, resolvedPort));
    }

    private static Optional<String> hostOf(String raw) {
        String host = raw.toLowerCase(Locale.ROOT);
        return HOST.matcher(host).matches() ? Optional.of(host) : Optional.empty();
    }
}
