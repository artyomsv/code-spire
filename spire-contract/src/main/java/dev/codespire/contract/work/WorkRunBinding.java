package dev.codespire.contract.work;

import java.util.Objects;
import java.util.UUID;

/** Immutable identity of the prepared build whose workspace may later be published. */
public record WorkRunBinding(String workItemId,long generation,UUID buildAttemptId,String preparationBinding) {
    public WorkRunBinding {
        if(workItemId==null || workItemId.isBlank())throw new IllegalArgumentException("A work item identity is required");
        if(generation<1)throw new IllegalArgumentException("A positive work generation is required");
        Objects.requireNonNull(buildAttemptId,"A build attempt is required");
        if(preparationBinding==null || !preparationBinding.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("A full prepared-task binding is required");
    }

    /** Length-delimited identity for trusted runtime labels; no tracker content or credential. */
    public String publicationKey() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : java.util.List.of(workItemId, Long.toString(generation),
                    buildAttemptId.toString(), preparationBinding)) {
                byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
