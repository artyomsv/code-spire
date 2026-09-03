package dev.codespire.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.workspace.ChangedPath;
import dev.codespire.secrets.SecretScrub;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JSON line per outcome, on stdout.
 *
 * <p>The worker reads this from the container's log stream. Nothing is extracted from the pod and
 * nothing is written to a shared volume (ADR-039) — the publisher writes to no volume at all, which
 * is what lets {@code /handoff} be mounted read-only to it.
 */
public final class OutcomeWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;

    /**
     * Removes the credential from every failure detail, in each form it can be rendered in.
     *
     * <p>Shared with the run worker rather than hand-rolled here. The two copies had drifted:
     * this one had no length floor, redacted in no particular order, and handled a single
     * credential, while the worker's did all three — and this is the container holding the git
     * WRITE token. One consequence of adopting the shared rules is deliberate and stated at
     * {@link SecretScrub#MIN_SECRET_LENGTH}: a secret below that length is no longer scrubbed,
     * because redacting a short string makes a failure detail unreadable. No forge issues a
     * token that short.
     */
    private final SecretScrub scrub;

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
        this.out = out;
        this.scrub = secret == null || secret.isEmpty()
                ? SecretScrub.none()
                : SecretScrub.of(List.of(new SecretScrub.Credential(username, secret)));
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
        write(entry("event", "failed", "cause", cause, "detail", scrub.clean(detail)));
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
