package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Pinned references and execution coordinates only; tracker artifact text never enters aggregate history. */
public record WorkPreparation(Artifact specification, Artifact plan, String baseBranch, String baseCommit,
                              String harness, String model, String registeredBy) {
    public record Artifact(WorkIssueLocation location, String sha256) {
        public Artifact {
            Objects.requireNonNull(location, "Artifact location is required");
            Objects.requireNonNull(location.ref(), "Artifact identity is required");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("A full artifact SHA-256 is required");
        }
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
    }
    /** Length-delimited fields keep distinct references and build coordinates distinct. */
    public String binding() {
        StringBuilder value = new StringBuilder();
        for (Artifact artifact : java.util.List.of(specification, plan)) {
            var ref = artifact.location().ref();
            for (String part : java.util.List.of(ref.type().name(), ref.origin(), ref.projectId(), ref.issueId(), artifact.sha256()))
                value.append(part.length()).append(':').append(part);
        }
        for (String part : java.util.List.of(baseBranch, baseCommit, harness, model)) value.append(part.length()).append(':').append(part);
        return digest(value.toString());
    }
    public static String digest(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
