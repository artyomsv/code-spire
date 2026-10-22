package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkLanding;
import dev.codespire.orchestrator.factory.LandSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * The ticket after land (spec §7.7, A1): one comment, then under the close rule a close where the tracker can close
 * one. Enqueued in the transaction that appends WORK_ITEM_LANDED.
 */
@ApplicationScoped
public class WorkLandTicket {
    @Inject WorkSourceRegistry sources;
    @Inject WorkSourceEffects effects;
    @Inject LandSettings landSettings;

    /**
     * Enqueues the ticket rule's effects for {@code landed}, bound to WORK_ITEM_LANDED's revision, which the projection
     * row holds once the decision is appended. {@code handle} is the merger's observed handle, null when unresolved.
     *
     * @return true when the rule is close and the tracker cannot close: a Jira ticket needs a configured transition, so
     *     it keeps its status and the land row says so
     */
    boolean enqueue(Connection c,WorkItemEvent landed,String handle) throws SQLException {
        long revision;
        try(PreparedStatement ps=c.prepareStatement("SELECT revision FROM work_item WHERE id=?")) {
            ps.setString(1,landed.workItemId());try(ResultSet rs=ps.executeQuery()){rs.next();revision=rs.getLong(1);}
        }
        WorkLanding landing=landed.progress().execution().landing();
        boolean close=landSettings.get(c,landed.repositoryId()).ticketRule()==LandSettings.TicketRule.CLOSE;
        Optional<String> transition=close?WorkSourceEffects.closeTransition(sources.get(c,landed.sourceId(),false).orElseThrow(NotFoundException::new).type()):Optional.empty();
        String comment=transition.isPresent()?WorkSourceEffects.landedCommentBeforeClose(landing,handle):WorkSourceEffects.landedCommentKeepingStatus(landing,handle);
        effects.enqueuePostLand(c,new WorkSourceEffects.PostLand(landed,revision,new WorkSourceEffects.Intent(WorkSourceEffects.Kind.COMMENT,comment)));
        if(transition.isEmpty())return close;
        effects.enqueuePostLand(c,new WorkSourceEffects.PostLand(landed,revision,new WorkSourceEffects.Intent(WorkSourceEffects.Kind.TRANSITION,transition.orElseThrow())));
        return false;
    }
}
