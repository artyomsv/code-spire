package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunVerification;
import dev.codespire.contract.work.WorkVerification;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * What the last verify of an item found, for the build that retries it (M4, spec §4.4). Read from the verify
 * outbox, not from progress: starting a build clears the execution, and the next claim overwrites the run.
 */
@ApplicationScoped
public class WorkVerifyHistory {
    /** The retry prompt's share: enough output to act on, never a log's worth of prompt. */
    static final int MAX_PROMPT_SECTION_CHARS = 8000;
    private static final String SHORTENED = "\n[output shortened]";

    @Inject EncryptionService encryption;
    @Inject ObjectMapper mapper;

    /** @param head the checkpoint the failed verify checked, which a retry starts from */
    public record Previous(String runId, String head, WorkVerification verification) {}

    /** The latest applied verify of this generation that did not pass, if any. Caller holds the item lock. */
    public Optional<Previous> lastNotPassed(Connection c, String workItemId, long generation) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT attempt_id,run_id,head,result FROM work_verify_effect
                WHERE work_item_id=? AND generation=? AND state='applied' AND outcome<>'PASSED' AND reason IS NULL
                ORDER BY created_at DESC LIMIT 1
                """)) {
            ps.setString(1, workItemId);
            ps.setLong(2, generation);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                UUID attempt = rs.getObject(1, UUID.class);
                RunVerification.RunWorkVerified result = mapper.readValue(
                        encryption.decrypt(rs.getBytes(4), WorkVerifyResults.aad(attempt)), RunVerification.RunWorkVerified.class);
                return Optional.of(new Previous(rs.getString(2), rs.getString(3), result.verification()));
            } catch (IOException unreadable) {
                throw new IllegalStateException("The last verify of " + workItemId + " could not be read", unreadable);
            }
        }
    }

    /** Whether a retry can continue from the previous checkpoint, or must start again from the base. */
    public static boolean checkpointReadable(WorkVerification verification) {
        return !"checkpoint_missing".equals(verification.reason());
    }

    /** The section a retried build's prompt gains: what failed, and its output, bounded. */
    public static String promptSection(WorkVerification verification) {
        StringBuilder section = new StringBuilder("\n\nThe previous build of this task did not pass verification.\n");
        section.append(checkpointReadable(verification)
                ? "This build starts from that build's last commit. Fix what the checks found.\n"
                : "Its commits could not be read, so this build starts again from the base.\n");
        for (WorkVerification.CheckResult check : verification.checks()) {
            section.append("\nCommand: ").append(check.command())
                    .append("\nExit code: ").append(check.exitCode() == null ? "did not run" : check.exitCode());
            if (check.exitCode() != null && check.exitCode() != 0 && !check.outputTail().isEmpty())
                section.append("\nLast output:\n").append(check.outputTail());
        }
        if (verification.checks().isEmpty()) section.append("\nReason: ").append(verification.reason());
        if (section.length() <= MAX_PROMPT_SECTION_CHARS) return section.toString();
        return section.substring(0, MAX_PROMPT_SECTION_CHARS - SHORTENED.length()) + SHORTENED;
    }
}
