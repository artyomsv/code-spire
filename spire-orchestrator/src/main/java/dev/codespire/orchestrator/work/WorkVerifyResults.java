package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunVerification;
import dev.codespire.contract.work.WorkVerification;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Applies what a verify unit found (M4). The result is stored, encrypted, before it is applied, and applied at
 * most once per attempt: a redelivery finds the row already applied. A result for another run or head matches
 * no row and changes nothing.
 */
@ApplicationScoped
public class WorkVerifyResults {
    private static final Logger LOG = Logger.getLogger(WorkVerifyResults.class);
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;
    @Inject ObjectMapper mapper;
    @Inject WorkItemTransitions transitions;

    @Incoming("run-verifications-in")
    @Blocking
    public CompletionStage<Void> onResult(Message<RunVerification> message) {
        if (message.getPayload() instanceof RunVerification.RunWorkVerified verified) {
            try {
                apply(verified);
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "verification %s for %s could not be applied", verified.verification().attemptId(), verified.runId());
                return message.nack(failure);
            }
        }
        return message.ack();
    }

    public void apply(RunVerification.RunWorkVerified verified) {
        WorkVerification verification = verified.verification();
        String item = report(verified);
        if (item == null) item = reportedItem(verification.attemptId());
        if (item == null) {
            LOG.warnf("verification %s for %s matches no open verify attempt; ignored", verification.attemptId(), verified.runId());
            return;
        }
        var outcome = transitions.verified(item, verified);
        // An authority outage (503) is retried by the sweep; any other answer is final for this attempt.
        if (outcome.status() != 503) applied(verification.attemptId(), outcome.status() == 200 ? null : outcome.reason());
    }

    /** Re-applies results stored before a crash between storing and applying. */
    @Scheduled(every = "${spire.work-run-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void recover() {
        List<UUID> reported = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT attempt_id FROM work_verify_effect WHERE state='reported' ORDER BY created_at LIMIT 20");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) reported.add(rs.getObject(1, UUID.class));
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        // One row at a time: a row that cannot be applied must not starve every newer result behind it.
        for (UUID attempt : reported) {
            try {
                stored(attempt).ifPresent(this::apply);
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "verification %s could not be re-applied; the next sweep tries again", attempt);
            }
        }
    }

    /** Stores the result once, on the attempt whose run and head it names. @return the work item, or null */
    private String report(RunVerification.RunWorkVerified verified) {
        WorkVerification verification = verified.verification();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE work_verify_effect SET state='reported',outcome=?,reason=?,result=?
                WHERE attempt_id=? AND run_id=? AND head=? AND state IN ('pending','sent','uncertain') RETURNING work_item_id
                """)) {
            ps.setString(1, verification.outcome().name());
            ps.setString(2, verification.reason());
            ps.setBytes(3, encryption.encrypt(mapper.writeValueAsBytes(verified), aad(verification.attemptId())));
            ps.setObject(4, verification.attemptId());
            ps.setString(5, verified.runId());
            ps.setString(6, verification.head());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        catch (IOException failure) { throw new IllegalStateException("Cannot encode verification " + verification.attemptId(), failure); }
    }

    /** A row stored by an earlier delivery but not yet applied: the crash window between the two. */
    private String reportedItem(UUID attempt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT work_item_id FROM work_verify_effect WHERE attempt_id=? AND state='reported'")) {
            ps.setObject(1, attempt);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private java.util.Optional<RunVerification.RunWorkVerified> stored(UUID attempt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT result FROM work_verify_effect WHERE attempt_id=? AND state='reported'")) {
            ps.setObject(1, attempt);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(mapper.readValue(encryption.decrypt(rs.getBytes(1), aad(attempt)), RunVerification.RunWorkVerified.class));
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        catch (IOException failure) { throw new IllegalStateException("Cannot decode verification " + attempt, failure); }
    }

    private void applied(UUID attempt, String reason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE work_verify_effect SET state='applied',reason=? WHERE attempt_id=? AND state='reported'")) {
            ps.setString(1, reason);
            ps.setObject(2, attempt);
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    static String aad(UUID attempt) {
        return "work-verify-result:" + attempt;
    }
}
