package dev.codespire.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.workspace.ChangedPath;
import dev.codespire.secrets.SecretScrub;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One JSON line per outcome, on stdout.
 *
 * <p>The worker reads this from the container's log stream. Nothing is extracted from the pod and
 * nothing is written to a shared volume (ADR-039) — the publisher writes to no volume at all, which
 * is what lets {@code /handoff} be mounted read-only to it.
 */
public final class OutcomeWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The longest line the run worker receives whole: {@code DockerRunRuntime.MAX_LINE_CHARS}, which delivers a longer
     * one clipped, and {@code PublisherOutcome} skips a clipped line it cannot parse. The artifact's 32 KiB file cap
     * counts bytes, and JSON escaping can grow them up to six times, so the line is checked as well.
     */
    static final int MAX_LINE_CHARS = 64 * 1024;

    /**
     * C0 control characters other than newline and tab, which Jackson escapes as six characters each. The orchestrator
     * strips them from untrusted text anyway (amendment 13); dropping them here keeps an artifact full of copied
     * terminal colour codes inside one line.
     */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F]");

    private final PrintStream out;

    /**
     * Removes the credential from every failure detail, in each form it can be rendered in.
     *
     * <p>Shared with the run worker rather than hand-rolled here. The two copies had drifted:
     * this one had no length floor, redacted in no particular order, and handled a single
     * credential, while the worker's did all three — and this is the container holding the git
     * WRITE token.
     *
     * <p>The shared class briefly imposed a length floor that this copy never had, which silently
     * stopped scrubbing a short forge password — Gitea and Forgejo issue them. That floor is gone
     * (see {@link SecretScrub#MIN_PLAUSIBLE_SECRET_LENGTH}), so the consolidation now loses this
     * copy nothing at all and gains it ordering, multiple credentials, and one place to fix.
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

    /** Trusted publisher observation; the checkpoint remains unpublished. */
    public void checkpoint(String head, List<ChangedPath> changed) {
        write(entry("event", "checkpoint", "head", head, "changed", describe(changed)));
    }

    /** A held plan step that committed nothing (M4 slice 3, §6.7): its checkpoint is where it started. */
    public void emptyCheckpoint(String head) {
        write(entry("event", "checkpoint", "head", head, "changed", List.of(), "empty", true));
    }

    /** The verify workspace holds exactly {@code head} (M4, {@code spire-verify-prepare}). */
    public void prepared(String head) {
        write(entry("event", "prepared", "head", head));
    }

    /**
     * @param blocked every refused path WITH what happened to it — "ci.yml was blocked" does not
     *                tell an operator whether the factory edited that workflow or deleted it
     */
    public void refused(List<ChangedPath> blocked, List<ChangedPath> changed) {
        write(entry("event", "gate_refused", "blocked", describe(blocked), "changed", describe(changed)));
    }

    /**
     * The agent's specification or plan, read from the handoff (M4 slice 3), without its control characters. Text that
     * would still pass {@link #MAX_LINE_CHARS} once escaped is refused as {@code TOO_LARGE} rather than lost in transport.
     */
    public void artifact(String text) {
        // Controls go first: a credential split by an escape byte would otherwise pass the scrub and close up after it.
        String cleaned = scrub.clean(CONTROL_CHARACTERS.matcher(text).replaceAll(""));
        String line = serialise(entry("event", "artifact", "text", cleaned));
        if (line.length() > MAX_LINE_CHARS) {
            handoffFileRefused(HandoffFiles.ARTIFACT, HandoffFiles.Refusal.TOO_LARGE.name());
            return;
        }
        emit(line);
    }

    /** The agent's step summary, as the JSON text it wrote; parsed and capped by the run worker. */
    public void stepSummary(String json) {
        write(entry("event", "step_summary", "json", scrub.clean(json)));
    }

    /** A read-only run that committed anyway: {@code bundles} were left on the handoff and none was read. */
    public void repositoryChanged(int bundles) {
        write(entry("event", "repository_changed", "bundles", bundles));
    }

    /** A handoff file that was not read, and why ({@code SYMLINK}, {@code NOT_REGULAR}, ...). */
    public void handoffFileRefused(String file, String why) {
        write(entry("event", "handoff_file_refused", "file", file, "why", why));
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
        emit(serialise(payload));
    }

    private static String serialise(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // A publisher that cannot describe what it did is still a publisher that pushed. Never
            // crash here: the alternative is losing the push AND the report. Only serialization can
            // fail here -- println on a PrintStream swallows its own errors by contract.
            return "{\"event\":\"failed\",\"cause\":\"REPORT_FAILED\"}";
        }
    }

    private void emit(String line) {
        out.println(line);
        out.flush();
    }
}
