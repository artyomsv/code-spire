package dev.codespire.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.workspace.ChangedPath;

import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JSON line per outcome, on stdout.
 *
 * <p>The worker reads this from the container's log stream. Nothing is extracted from the pod and
 * nothing is written to a shared volume (ADR-038) — the publisher writes to no volume at all, which
 * is what lets {@code /handoff} be mounted read-only to it.
 */
public final class OutcomeWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REDACTED = "***";

    private final PrintStream out;

    /** The credential's username; with it, the Basic-auth pair is scrubbed too. Null when unknown. */
    private final String username;

    /** Scrubbed from every failure detail; null when there is nothing to protect yet. */
    private final String secret;

    public OutcomeWriter() {
        this(System.out);
    }

    /** Injectable so a test can read what was written instead of scraping a real stdout. */
    public OutcomeWriter(PrintStream out) {
        this(out, null);
    }

    /**
     * @param secret the git credential, so no failure detail can carry it — a transport exception
     *               quotes the URL it tried, and this line is what the worker records as the run's
     *               failure. Refusing userinfo in the URI is the front door; this is the one behind it.
     */
    public OutcomeWriter(PrintStream out, String secret) {
        this(out, null, secret);
    }

    public OutcomeWriter(PrintStream out, String username, String secret) {
        this.username = username;
        this.out = out;
        this.secret = secret;
    }

    public void pushed(String ref, List<ChangedPath> changed) {
        write(entry("event", "pushed", "ref", ref, "changed", describe(changed)));
    }

    /**
     * @param blocked every refused path WITH what happened to it — "ci.yml was blocked" does not
     *                tell an operator whether the factory edited that workflow or deleted it
     */
    public void refused(List<ChangedPath> blocked, List<ChangedPath> changed) {
        write(entry("event", "gate_refused", "blocked", describe(blocked), "changed", describe(changed)));
    }

    public void failed(String cause, String detail) {
        write(entry("event", "failed", "cause", cause, "detail", scrub(detail)));
    }

    /**
     * Removes the credential from a message in every form JGit can render it, not only the literal.
     *
     * <p>The literal alone was the whole scrub, and it is narrower than it looks: JGit speaks HTTP
     * Basic, so a transport error can carry the token percent-encoded inside a URI or Base64-encoded
     * as the {@code Authorization} value, and neither matches the raw string. The forge tokens in
     * use today are alphanumeric and survive percent-encoding unchanged, so the first form is
     * defence in depth; the Base64 form is a real gap — {@code user:token} encodes to a string that
     * shares no substring with the token.
     */
    private String scrub(String detail) {
        if (detail == null || secret == null || secret.isEmpty()) {
            return detail;
        }
        String scrubbed = detail.replace(secret, REDACTED);
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        if (!encoded.equals(secret)) {
            scrubbed = scrubbed.replace(encoded, REDACTED);
        }
        if (username != null) {
            String basic = Base64.getEncoder().encodeToString(
                    (username + ":" + secret).getBytes(StandardCharsets.UTF_8));
            scrubbed = scrubbed.replace(basic, REDACTED);
        }
        return scrubbed;
    }

    private static List<Map<String, String>> describe(List<ChangedPath> paths) {
        return paths.stream()
                .map(path -> Map.of("path", path.path(), "kind", path.kind().name()))
                .toList();
    }

    private static Map<String, Object> entry(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            payload.put((String) keyValues[i], keyValues[i + 1]);
        }
        return payload;
    }

    private void write(Map<String, Object> payload) {
        try {
            out.println(JSON.writeValueAsString(payload));
            out.flush();
        } catch (JsonProcessingException e) {
            // A publisher that cannot describe what it did is still a publisher that pushed. Never
            // crash here: the alternative is losing the push AND the report. Only serialization can
            // fail in this block -- println on a PrintStream swallows its own errors by contract.
            out.println("{\"event\":\"failed\",\"cause\":\"REPORT_FAILED\"}");
            out.flush();
        }
    }
}
