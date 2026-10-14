package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunVerification;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The verify claim and its result outbox (M4). The claim shares {@code runworker.run_claim} with builds, under
 * the slot {@code verify:<attempt>} beside the build's own {@code execute} slot, so a redelivered command runs
 * no second verify. Payloads are encrypted: a result's tails can quote source.
 */
@ApplicationScoped
public class WorkVerifyStore {
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;

    static String slot(UUID attempt) {
        return "verify:" + attempt;
    }

    /** @return false when this attempt was already claimed: a redelivery, not a second verify */
    public boolean claim(RunCommand.VerifyWork command) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement claim = c.prepareStatement(
                    "INSERT INTO runworker.run_claim(run_id,slot) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                claim.setString(1, command.runId());
                claim.setString(2, slot(command.attemptId()));
                if (claim.executeUpdate() == 0) { c.rollback(); return false; }
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO runworker.work_verify(attempt_id,run_id,command,state) VALUES (?,?,?,'running')")) {
                insert.setObject(1, command.attemptId());
                insert.setString(2, command.runId());
                insert.setBytes(3, encode(command.attemptId(), "command", command));
                insert.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException failure) { throw database(failure); }
    }

    public void finish(RunVerification.RunWorkVerified result) {
        UUID attempt = result.verification().attemptId();
        update("UPDATE runworker.work_verify SET state='finished',result=?,updated_at=now() WHERE attempt_id=? AND state='running'",
                encode(attempt, "result", result), attempt);
    }

    public List<RunVerification.RunWorkVerified> unsent() {
        List<RunVerification.RunWorkVerified> results = new ArrayList<>();
        for (Row row : rows("SELECT attempt_id,result FROM runworker.work_verify WHERE state='finished' AND sent_at IS NULL ORDER BY created_at LIMIT 20"))
            results.add(decode(row.attempt(), "result", row.payload(), RunVerification.RunWorkVerified.class));
        return List.copyOf(results);
    }

    public void sent(UUID attempt) {
        update("UPDATE runworker.work_verify SET sent_at=now() WHERE attempt_id=? AND sent_at IS NULL", attempt);
    }

    public List<RunCommand.VerifyWork> running() {
        List<RunCommand.VerifyWork> commands = new ArrayList<>();
        for (Row row : rows("SELECT attempt_id,command FROM runworker.work_verify WHERE state='running' ORDER BY created_at LIMIT 20"))
            commands.add(decode(row.attempt(), "command", row.payload(), RunCommand.VerifyWork.class));
        return List.copyOf(commands);
    }

    private record Row(UUID attempt, byte[] payload) {}

    private List<Row> rows(String sql) {
        List<Row> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(new Row(rs.getObject(1, UUID.class), rs.getBytes(2)));
        } catch (SQLException failure) { throw database(failure); }
        return rows;
    }

    private byte[] encode(UUID attempt, String part, Object value) {
        try { return encryption.encrypt(mapper.writeValueAsBytes(value), "work-verify:" + attempt + ":" + part); }
        catch (IOException failure) { throw new IllegalStateException("Cannot encode verify " + attempt, failure); }
    }

    private <T> T decode(UUID attempt, String part, byte[] value, Class<T> type) {
        try { return mapper.readValue(encryption.decrypt(value, "work-verify:" + attempt + ":" + part), type); }
        catch (IOException failure) { throw new IllegalStateException("Cannot decode verify " + attempt, failure); }
    }

    private void update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException failure) { throw database(failure); }
    }

    private static IllegalStateException database(SQLException failure) {
        return new IllegalStateException("A verify attempt could not be recorded", failure);
    }
}
