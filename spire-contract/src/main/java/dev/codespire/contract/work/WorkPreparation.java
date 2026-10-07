package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pinned references and execution coordinates only; tracker artifact text never enters aggregate history. */
public record WorkPreparation(Artifact specification, Artifact plan, String baseBranch, String baseCommit,
                              String harness, String model, String registeredBy, int bindingVersion,
                              String effort, String payWith, List<String> verifyCommands, long verifyTimeoutSeconds) {

    /** Where an artifact's approved bytes live. Absent in stored history means {@link Origin#TRACKER}. */
    public enum Origin {
        /** A ticket in the work source. The digest is over the ticket body, re-read before every use. */
        TRACKER,
        /**
         * Bytes this deployment stored when it composed the task. The digest is over those bytes, so an
         * edit to the ticket they were taken from cannot change what an approval binds.
         */
        STORED
    }

    /**
     * @param location where the text came from. Present for both origins: a STORED artifact keeps the
     *     ticket it was composed from, so a decision can still be traced to the ticket a person wrote.
     * @param storedId the stored bytes' own immutable identity, for {@link Origin#STORED} only. It is
     *     part of the binding, because re-preparing writes NEW rows rather than replacing old ones —
     *     an earlier gate and a held run keep pointing at the bytes they approved.
     */
    public record Artifact(WorkIssueLocation location, String sha256, Origin origin, UUID storedId) {
        public Artifact(WorkIssueLocation location, String sha256) { this(location, sha256, Origin.TRACKER, null); }

        public Artifact {
            origin = origin == null ? Origin.TRACKER : origin;
            Objects.requireNonNull(location, "Artifact location is required");
            Objects.requireNonNull(location.ref(), "Artifact identity is required");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("A full artifact SHA-256 is required");
            if (origin == Origin.STORED && storedId == null) throw new IllegalArgumentException("A stored artifact needs its stored identity");
            if (origin == Origin.TRACKER && storedId != null) throw new IllegalArgumentException("A tracker artifact has no stored identity");
        }
    }

    /**
     * The algorithm a preparation's {@link #binding()} uses. Version 1 is what M3 shipped and what every
     * stored preparation carries; it must keep producing the same hash for ever, because a gate stores
     * that hash ({@code WorkGate.artifactOf}) and answering a gate compares it against a RECOMPUTED one.
     * Adding fields to the hash instead of versioning it would supersede every open decision and
     * invalidate every held run, silently.
     */
    public static final int TRACKER_BINDING = 1;

    /** Adds the artifact origin and the stored identity, for preparations this deployment composes. */
    public static final int STORED_BINDING = 2;

    /**
     * Adds the thinking level (M3.5 part M).
     *
     * <p>A version of its own, for the reason the others exist: a gate stores the binding and answering
     * it compares against a RECOMPUTED one, so folding the level into version 2 would change the hash of
     * every composed preparation already waiting on a decision. It belongs in the binding at all because
     * it changes what a build costs and how hard the model works — the same reason the model does — and
     * because the vendor's CLI does not check it: measured 2026-09-22, a nonsense level is accepted and
     * echoed back. So the level an operator approved is the one the build runs, or nothing is.
     */
    public static final int EFFORT_BINDING = 3;

    /**
     * Adds how the build pays (M3.5 part F). Part of the binding because it decides what a build costs:
     * a plan approved to run on a subscription must not start billing an API key per token because the
     * build setup changed after the decision. Its own version for the reason the others have one.
     */
    public static final int PAY_WITH_BINDING = 4;

    /**
     * Adds the verify commands and their time limit (M4). They decide what "passed" means for this build,
     * so a gate approves the exact list, in its order. Its own version for the reason the others have one.
     */
    public static final int VERIFY_BINDING = 5;

    /** The bounds the run worker applies; refused here so a preparation never carries what a verify refuses. */
    public static final int MAX_VERIFY_COMMANDS = 20, MAX_VERIFY_COMMAND_CHARS = 1000;

    /** Every preparation written before M4 carries no verify commands. */
    public WorkPreparation(Artifact specification, Artifact plan, String baseBranch, String baseCommit,
                           String harness, String model, String registeredBy, int bindingVersion, String effort, String payWith) {
        this(specification, plan, baseBranch, baseCommit, harness, model, registeredBy, bindingVersion, effort, payWith, List.of(), 0);
    }

    /** Every preparation written before payment modes existed pays with an API key. */
    public WorkPreparation(Artifact specification, Artifact plan, String baseBranch, String baseCommit,
                           String harness, String model, String registeredBy, int bindingVersion, String effort) {
        this(specification, plan, baseBranch, baseCommit, harness, model, registeredBy, bindingVersion, effort, null);
    }

    public WorkPreparation(Artifact specification, Artifact plan, String baseBranch, String baseCommit,
                           String harness, String model, String registeredBy) {
        this(specification, plan, baseBranch, baseCommit, harness, model, registeredBy, TRACKER_BINDING, null, null);
    }

    /** Every preparation written before thinking levels existed carries the model's own default. */
    public WorkPreparation(Artifact specification, Artifact plan, String baseBranch, String baseCommit,
                           String harness, String model, String registeredBy, int bindingVersion) {
        this(specification, plan, baseBranch, baseCommit, harness, model, registeredBy, bindingVersion, null, null);
    }

    public WorkPreparation {
        Objects.requireNonNull(specification, "Specification is required");
        Objects.requireNonNull(plan, "Plan is required");
        if (baseBranch == null || baseBranch.isBlank() || harness == null || harness.isBlank()
                || model == null || model.isBlank() || registeredBy == null || registeredBy.isBlank())
            throw new IllegalArgumentException("Build coordinates and the registering operator are required");
        if (baseCommit == null || !baseCommit.matches("[0-9a-fA-F]{40}"))
            throw new IllegalArgumentException("A full base commit is required");
        baseCommit = baseCommit.toLowerCase(java.util.Locale.ROOT);
        // Absent in older stored JSON, where every artifact was a tracker ticket.
        bindingVersion = bindingVersion == 0 ? TRACKER_BINDING : bindingVersion;
        if (bindingVersion < TRACKER_BINDING || bindingVersion > VERIFY_BINDING)
            throw new IllegalArgumentException("Unknown preparation binding version " + bindingVersion);
        effort = ThinkingLevel.normalise(effort);
        // A level under a version that does not hash it would be carried to the build without being part
        // of what was approved -- exactly what the version exists to prevent.
        if (effort != null && bindingVersion < EFFORT_BINDING)
            throw new IllegalArgumentException("A thinking level needs binding version " + EFFORT_BINDING);
        payWith = PayWith.normalise(payWith);
        // A subscription under a version that does not hash it would reach the build without being part
        // of what was approved.
        if (!payWith.equals(PayWith.API_KEY) && bindingVersion < PAY_WITH_BINDING)
            throw new IllegalArgumentException("Paying with " + payWith + " needs binding version " + PAY_WITH_BINDING);
        if (bindingVersion == TRACKER_BINDING
                && (specification.origin() != Origin.TRACKER || plan.origin() != Origin.TRACKER))
            throw new IllegalArgumentException("A version 1 binding describes tracker artifacts only");
        // Absent in older stored JSON, where no preparation carried checks.
        verifyCommands = verifyCommands == null ? List.of() : List.copyOf(verifyCommands);
        // Commands under a version that does not hash them would decide "passed" without being approved.
        if (bindingVersion < VERIFY_BINDING && (!verifyCommands.isEmpty() || verifyTimeoutSeconds != 0))
            throw new IllegalArgumentException("Verify commands need binding version " + VERIFY_BINDING);
        if (bindingVersion >= VERIFY_BINDING) {
            if (verifyTimeoutSeconds < 1)
                throw new IllegalArgumentException("A version " + VERIFY_BINDING + " preparation carries its verify time limit");
            if (verifyCommands.size() > MAX_VERIFY_COMMANDS || verifyCommands.stream().anyMatch(command -> command.isBlank()
                    || command.length() > MAX_VERIFY_COMMAND_CHARS || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0))
                throw new IllegalArgumentException("Verify commands are 0-" + MAX_VERIFY_COMMANDS
                        + " single lines of at most " + MAX_VERIFY_COMMAND_CHARS + " characters");
        }
    }

    /** Length-delimited fields keep distinct references and build coordinates distinct. */
    public String binding() {
        StringBuilder value = new StringBuilder();
        for (Artifact artifact : java.util.List.of(specification, plan)) {
            var ref = artifact.location().ref();
            for (String part : java.util.List.of(ref.type().name(), ref.origin(), ref.projectId(), ref.issueId(), artifact.sha256()))
                value.append(part.length()).append(':').append(part);
            // Version 1 hashed tracker coordinates only, and must keep doing so: an existing gate holds
            // that exact hash, and a recomputed one that differs supersedes the decision it describes.
            if (bindingVersion >= STORED_BINDING) {
                for (String part : java.util.List.of(artifact.origin().name(),
                        artifact.storedId() == null ? "" : artifact.storedId().toString()))
                    value.append(part.length()).append(':').append(part);
            }
        }
        for (String part : java.util.List.of(baseBranch, baseCommit, harness, model)) value.append(part.length()).append(':').append(part);
        // Versions 1 and 2 must keep producing exactly the hashes their open gates hold.
        if (bindingVersion >= EFFORT_BINDING) {
            String level = effort == null ? "" : effort;
            value.append(level.length()).append(':').append(level);
        }
        if (bindingVersion >= PAY_WITH_BINDING) value.append(payWith.length()).append(':').append(payWith);
        // The count first, so ["ab"] and ["a","b"] can never hash alike.
        if (bindingVersion >= VERIFY_BINDING) {
            value.append(verifyCommands.size()).append('#');
            for (String command : verifyCommands) value.append(command.length()).append(':').append(command);
            String limit = Long.toString(verifyTimeoutSeconds);
            value.append(limit.length()).append(':').append(limit);
        }
        return digest(value.toString());
    }

    public static String digest(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
