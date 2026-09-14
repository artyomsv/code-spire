package dev.codespire.contract.event;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worksource.WorkIssueRef;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned length-prefixed identity. Credentials, handles, issue keys and titles are absent by design. */
public final class WorkItemIds {
    private WorkItemIds() {}

    public static String of(ScmType scm, String forgeOrigin, RepoRef repository, WorkIssueRef issue) {
        String[] parts = { scm.providerType(), ForgeOrigin.of(forgeOrigin), repository.workspace(), repository.slug(),
                issue.type().name(), ForgeOrigin.of(issue.origin()), issue.projectId(), issue.issueId() };
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(1);
            for (String part : parts) {
                if (part == null || part.isBlank()) throw new IllegalArgumentException("Every work-item identity coordinate is required");
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                output.writeInt(value.length);
                output.write(value);
            }
            return "work-v1-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
