package dev.codespire.runtime.docker;

import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The egress proxy's tinyproxy configuration and filter, rendered per run.
 *
 * <p>{@code Allow} limits clients to the unit's internal network. The proxy also sits on the default bridge, and
 * without it every container there could use it as a way out. Report mode has no filter, so every host is let through
 * and logged. Enforce mode denies by default and allows the declared hosts exactly.
 *
 * <p>The corporate upstream is the operator's value and may carry basic auth. tinyproxy 1.11 takes it as
 * {@code Upstream http user:password@host:port}, matched by its own pattern ({@code USERNAME "([^:]*)"},
 * {@code PASSWORD "([^@]*)"}) and turned into Basic authentication as written, with no decoding and no TLS. So the
 * credential is percent-decoded here, as the corporate transport decodes it, and a value that directive cannot hold
 * is refused, as is an https:// upstream. Neither refusal quotes the credential.
 */
final class TinyproxyConfig {

    /** The settings an operator sets the upstream with, named in every refusal so the message is actionable. */
    private static final String SETTINGS = "SPIRE_RUN_HTTPS_PROXY / SPIRE_RUN_HTTP_PROXY";

    private static final Pattern PROXY_URL =
            Pattern.compile("(?i)^(?:(https?)://)?(?:([^@/]*)@)?((?:[-a-z0-9._]+|\\[[0-9a-f:.]+\\]):\\d{1,5})/*$");

    private static final Pattern NO_PROXY_ENTRY = Pattern.compile("[A-Za-z0-9.:/-]{1,253}");

    /** What neither half of the credential may hold: tinyproxy's line would end, or parse differently. */
    private static final Pattern UNREPRESENTABLE = Pattern.compile("[\\s\"\\p{Cntrl}@]");

    private TinyproxyConfig() {
    }

    static String configFor(EgressPolicy egress, EnterpriseEnvironment enterprise, String subnet) {
        List<String> lines = new ArrayList<>(List.of(
                "Port " + EgressPolicy.PROXY_PORT,
                "Timeout 600",
                "MaxClients 64",
                // No LogFile, and that is what sends the log to stdout. tinyproxy opens a named log file through
                // create_file_safely, which refuses a symlink or anything not a regular file -- so "/dev/stdout"
                // failed, tinyproxy fell back to syslog, and with no syslog in the container every line was lost.
                "LogLevel Connect",
                "DisableViaHeader Yes",
                "Allow " + subnet));
        EgressPolicy.PORTS.stream().sorted().forEach(port -> lines.add("ConnectPort " + port));
        if (egress.mode() == EgressPolicy.Mode.ENFORCE) {
            lines.add("Filter \"/tmp/filter\"");
            lines.add("FilterDefaultDeny Yes");
        }
        lines.addAll(upstreamLines(enterprise));
        return String.join("\n", lines);
    }

    /** One anchored pattern per declared host; tinyproxy matches host names case-insensitively. */
    static String filterFor(EgressPolicy egress) {
        return String.join("\n", egress.hosts().stream().sorted()
                .map(host -> "^" + host.replace(".", "\\.") + "$")
                .toList());
    }

    /**
     * The corporate proxy to chain to, and the hosts it is not used for; empty when there is none. Also the check the
     * runtime makes before a unit is created, so a proxy the egress proxy cannot chain to fails the run at once.
     */
    static List<String> upstreamLines(EnterpriseEnvironment enterprise) {
        var upstream = enterprise.upstreamProxy();
        if (upstream.isEmpty()) {
            return List.of();
        }
        List<String> noProxy = enterprise.noProxy();
        if (noProxy.contains("*")) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("Upstream http " + upstreamTarget(upstream.orElseThrow()));
        for (String entry : noProxy) {
            if (NO_PROXY_ENTRY.matcher(entry).matches()) {
                lines.add("Upstream none \"" + entry + "\"");
            }
        }
        return lines;
    }

    /** {@code [user:password@]host:port}, the credential decoded, from the operator's proxy URL. */
    private static String upstreamTarget(String url) {
        Matcher parsed = PROXY_URL.matcher(url.strip());
        if (!parsed.matches()) {
            throw new IllegalArgumentException("The corporate proxy in " + SETTINGS + " is not of the form "
                    + "http://[user:password@]host:port, so the egress proxy cannot chain to it");
        }
        if ("https".equalsIgnoreCase(parsed.group(1))) {
            throw new IllegalArgumentException("The corporate proxy in " + SETTINGS + " is an https:// URL. The egress"
                    + " proxy (tinyproxy) reaches its upstream over plain HTTP only, and sending the proxy credential"
                    + " there unencrypted is not an option: give the proxy's plain-HTTP address, or set"
                    + " SPIRE_RUN_EGRESS_MODE=off");
        }
        String userInfo = parsed.group(2);
        String hostPort = parsed.group(3);
        return userInfo == null ? hostPort : credential(userInfo) + "@" + hostPort;
    }

    private static String credential(String userInfo) {
        int colon = userInfo.indexOf(':');
        String user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
        String password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        if (user.contains(":") || UNREPRESENTABLE.matcher(user).find()
                || (password != null && UNREPRESENTABLE.matcher(password).find())) {
            throw new IllegalArgumentException("The corporate proxy credential in " + SETTINGS + " holds a character"
                    + " tinyproxy's Upstream directive cannot carry (':' in the user name, '@', whitespace, a quote or"
                    + " a control character); use a service account whose credential has none");
        }
        return password == null ? user : user + ":" + password;
    }

    /** Percent-decoding as a URL's user information is decoded: '+' stays a plus. */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // The JDK's message quotes the input, which is the credential: replaced, not wrapped.
            throw new IllegalArgumentException("The corporate proxy credential in " + SETTINGS
                    + " has a malformed percent-escape");
        }
    }
}
