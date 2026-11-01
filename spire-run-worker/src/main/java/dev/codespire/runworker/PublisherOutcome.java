package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the publisher reported, folded from its stdout JSON lines.
 *
 * <p>Three rules, each learned from a defect. The LAST push wins, because every checkpoint pushes
 * the same branch and the latest ref is the branch's state. A refusal outranks any push: what was
 * pushed before the gate tripped is on the remote, but the run's outcome is the refusal. And a
 * TERMINAL failure after the last push empties the ref — a forge refusing checkpoint five leaves
 * the branch at four, and reporting four as the run's success hid the refusal entirely — while a
 * non-terminal one ({@code BUNDLE_UNREADABLE}: the publisher skips that bundle and reads on) leaves
 * the earlier push standing, because four checkpoints really are on the branch.
 *
 * <p>The path lists are bounded: a run that touches sixty thousand files would otherwise produce a
 * result larger than a Kafka record, and the only trace of its completion would be a log line.
 */
public final class PublisherOutcome {

    /** Well past any reviewable change; enough to keep the result under the broker's record size. */
    static final int MAX_PATHS = PublisherPaths.MAX_PATHS;

    /** Causes after which the publisher keeps reading — an earlier push is not undone by them. */
    private static final Set<String> NON_TERMINAL_CAUSES = Set.of("BUNDLE_UNREADABLE");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = Logger.getLogger(PublisherOutcome.class);

    /** The publisher's refusal for a file over its cap; the same word whichever side measured it. */
    private static final String TOO_LARGE = "TOO_LARGE";

    /** The publisher reports at most one refusal per handoff file; anything past this is not a real publisher. */
    private static final int MAX_REFUSALS = 8;

    /** The step summary (M4 slice 3), with the publisher's cap re-checked here in UTF-8 bytes. */
    private record HandoffText(String field, String file, int maxBytes) {
        static final HandoffText STEP_SUMMARY = new HandoffText("json", "step-summary.json", 8 * 1024);
    }

    private static final String ARTIFACT_FILE = "artifact.md";

    /** A draft's artifact cap, and every unit's that names none: the publisher's own default. */
    static final int DEFAULT_ARTIFACT_MAX_BYTES = 32 * 1024;

    public PublisherOutcome() {
        this(DEFAULT_ARTIFACT_MAX_BYTES);
    }

    private PublisherOutcome(int artifactMaxBytes) {
        this.artifact = new HandoffArtifact(artifactMaxBytes, why -> refuse(ARTIFACT_FILE, why));
    }

    /** An outcome whose artifact may be as large as its unit's cap (harness reviews, step 4). */
    static PublisherOutcome withArtifactCap(int maxBytes) {
        return new PublisherOutcome(maxBytes);
    }

    private final PublisherPaths paths = new PublisherPaths();

    private final Set<String> handoffRefusals = new LinkedHashSet<>();

    private final HandoffArtifact artifact;

    private String stepSummary;

    private int repositoryChanged;

    private String pushedRef;

    private String checkpointHead;

    private boolean failedAfterCheckpoint;

    /** Whether the last checkpoint said the build committed nothing (M4 slice 3, §6.7). */
    private boolean checkpointEmpty;

    private String failureCause;

    private String failureDetail;

    private boolean failedAfterPush;

    /**
     * Reads one line. An unparseable one is skipped — the publisher also logs plain text on the
     * same stream — but never silently: a report line that fails to parse is the one case where a
     * push could have happened with nothing here to say so, and a debug line is the only trace.
     */
    public void accept(String line) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JsonProcessingException e) {
            LOG.debugf("publisher wrote a non-JSON line, skipped: %s", e.getOriginalMessage());
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }
        switch (node.path("event").asText("")) {
            case "checkpoint" -> {
                String head = node.path("head").asText("");
                if (!head.matches("[0-9a-f]{40}")) {
                    checkpointHead = null;
                    checkpointEmpty = false;
                    failureCause = "PUBLISHER_FAILED";
                    failureDetail = "The publisher checkpoint did not carry a full head";
                    failedAfterCheckpoint = true;
                    break;
                }
                checkpointHead = head;
                checkpointEmpty = node.path("empty").asBoolean(false);
                paths.changed(node.path("changed"));
                failedAfterCheckpoint = false;
            }
            case "pushed" -> {
                pushedRef = node.path("ref").asText(null);
                paths.changed(node.path("changed"));
                failedAfterPush = false;
            }
            case "gate_refused" -> {
                paths.blocked(node.path("blocked"));
                paths.changed(node.path("changed"));
            }
            case "failed" -> {
                failureCause = node.path("cause").asText("PUBLISHER_FAILED");
                failureDetail = node.path("detail").asText("");
                failedAfterPush = pushedRef != null && !NON_TERMINAL_CAUSES.contains(failureCause);
                failedAfterCheckpoint = checkpointHead != null && !NON_TERMINAL_CAUSES.contains(failureCause);
            }
            case "artifact" -> artifact.single(node);
            case "artifact_part" -> artifact.part(node);
            case "step_summary" -> stepSummary = capped(node, HandoffText.STEP_SUMMARY);
            case "repository_changed" -> repositoryChanged = Math.max(0, node.path("bundles").asInt(0));
            case "handoff_file_refused" -> refuse(node.path("file").asText(""), node.path("why").asText(""));
            default -> {
            }
        }
    }

    public boolean refused() {
        return paths.anyBlocked();
    }

    public Optional<String> pushedRef() {
        return refused() || failedAfterPush ? Optional.empty() : Optional.ofNullable(pushedRef);
    }

    public Optional<String> checkpointHead() {
        return refused() || failedAfterCheckpoint ? Optional.empty() : Optional.ofNullable(checkpointHead);
    }

    /** Whether the observed checkpoint says the build committed nothing; false when there is no checkpoint to read. */
    public boolean checkpointEmpty() {
        return checkpointEmpty && checkpointHead().isPresent();
    }

    public List<String> changedPaths() {
        return paths.changedPaths();
    }

    public List<RunResult.BlockedChange> blocked() {
        return paths.blockedChanges();
    }

    /** Paths beyond {@link #MAX_PATHS} that the lists do not carry; logged by the launcher, never lost silently. */
    public int omittedPaths() {
        return paths.omittedPaths();
    }

    public Optional<String> failureCause() {
        return Optional.ofNullable(failureCause);
    }

    public String failureDetail() {
        return failureDetail == null ? "" : failureDetail;
    }

    /** The specification or plan a read-only run handed over; empty when none arrived or it was over the cap. */
    public Optional<String> artifact() {
        return artifact.text();
    }

    /** The step summary's JSON text, unparsed; empty when none arrived or it was over the cap. */
    public Optional<String> stepSummary() {
        return Optional.ofNullable(stepSummary);
    }

    /** Whether the artifact was over its unit's cap: a paid answer that came back without its text, never a lost run. */
    public boolean artifactOverCap() {
        return artifact.overCap();
    }

    /** Bundles a read-only run left on the handoff: a warning, never a failure, and never published. */
    public int repositoryChanged() {
        return repositoryChanged;
    }

    /** Each handoff file the publisher refused, or this parse dropped, as {@code file:why}. */
    public List<String> handoffRefusals() {
        List<String> all = new java.util.ArrayList<>(handoffRefusals);
        artifact.missing().ifPresent(why -> all.add(ARTIFACT_FILE + ":" + why));
        return List.copyOf(all);
    }

    /**
     * The last line of each kind wins, and a text over its cap is dropped and recorded as a refusal. Truncating it
     * would turn an over-long specification into a shorter one that looks valid.
     */
    private String capped(JsonNode node, HandoffText kind) {
        String text = node.path(kind.field()).asText(null);
        if (text == null) {
            return null;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > kind.maxBytes()) {
            refuse(kind.file(), TOO_LARGE);
            return null;
        }
        return text;
    }

    private void refuse(String file, String why) {
        // The publisher refusing the artifact for its size is the same fact as a total over the cap here: over cap.
        if (ARTIFACT_FILE.equals(file) && TOO_LARGE.equals(why)) {
            artifact.markOverCap();
        }
        if (handoffRefusals.size() < MAX_REFUSALS) {
            handoffRefusals.add(PublisherPaths.clip(file, PublisherPaths.MAX_PATH_CHARS) + ":"
                    + PublisherPaths.clip(why, PublisherPaths.MAX_KIND_CHARS));
        }
    }
}
