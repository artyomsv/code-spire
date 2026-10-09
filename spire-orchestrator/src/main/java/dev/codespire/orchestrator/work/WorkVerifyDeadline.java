package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunVerification;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkVerification;
import dev.codespire.orchestrator.factory.BuildDefaults;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gives up on a verify that went silent (review of PR #184). An uncertain send is never resent, and a command
 * the worker never claimed, or a result that could not be read, leaves the attempt sent for ever: builds have
 * an orphan watchdog, verify had nothing. Past its own time limit plus a grace, the attempt is reported
 * unverified, {@code verify_could_not_run}, through the normal result path, so a person sees a gate and can
 * retry or stop. A real result that arrives later matches no open row and changes nothing.
 */
@ApplicationScoped
public class WorkVerifyDeadline {
    private static final Logger LOG = Logger.getLogger(WorkVerifyDeadline.class);

    /** Room past the verify's own limit for queueing, an image pull and the result's way back. */
    static final Duration GRACE = Duration.ofMinutes(15);

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkVerifyResults results;

    private record Silent(UUID attempt, String item, String run, String head, Instant claimed) {}

    @Scheduled(every = "${spire.work-verify-deadline-interval:1m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        expire(Instant.now());
    }

    /** @return how many silent attempts were reported unverified */
    int expire(Instant now) {
        int expired = 0;
        for (Silent silent : silentAttempts()) {
            try {
                if (giveUp(silent, now)) expired++;
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "verify %s could not be given up; the next sweep tries again", silent.attempt());
            }
        }
        return expired;
    }

    private boolean giveUp(Silent silent, Instant now) {
        WorkItemEvent item = store.load(silent.item());
        long limit = item.preparation() != null && item.preparation().verifyTimeoutSeconds() > 0
                ? item.preparation().verifyTimeoutSeconds() : BuildDefaults.DEFAULT_VERIFY_SECONDS;
        if (silent.claimed().plusSeconds(limit).plus(GRACE).isAfter(now)) return false;
        if (item.progress().execution() == null) return false;
        LOG.warnf("verify %s for %s sent no result within its limit; reported as could not run", silent.attempt(), silent.run());
        results.apply(new RunVerification.RunWorkVerified(silent.run(), item.progress().execution().build(),
                new WorkVerification(silent.attempt(), silent.head(), WorkVerification.Outcome.UNVERIFIED, "verify_could_not_run", List.of())));
        return true;
    }

    private List<Silent> silentAttempts() {
        List<Silent> silent = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT attempt_id,work_item_id,run_id,head,created_at FROM work_verify_effect WHERE state IN ('sent','uncertain') ORDER BY created_at LIMIT 50");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) silent.add(new Silent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getTimestamp(5).toInstant()));
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        return silent;
    }
}
