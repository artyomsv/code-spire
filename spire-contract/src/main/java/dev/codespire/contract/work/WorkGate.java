package dev.codespire.contract.work;

import java.time.Instant;
import java.util.UUID;

/** Complete gate fact in the encrypted aggregate stream; its SQL row is a synchronous projection. */
public record WorkGate(UUID id, long version, String state, String phase, long generation, long itemRevision,
                       long policyRevision, WorkItemEvent.Authority authority, String artifact,
                       Instant openedAt, Instant expiresAt, String resolver, String channel, String answerKey, String note) {
    public WorkGate resolve(String outcome,String subject,String key,String decisionNote) {
        return resolve(outcome,subject,subject==null?"system":"dashboard",key,decisionNote);
    }
    public WorkGate resolve(String outcome,String subject,String answerChannel,String key,String decisionNote) {
        return new WorkGate(id,version+1,outcome,phase,generation,itemRevision,policyRevision,authority,artifact,
                openedAt,expiresAt,subject,answerChannel,key,decisionNote);
    }

    public static String artifactOf(WorkItemEvent item) {
        if ("land".equals(item.phase()))
            return item.progress().execution()==null?null:item.progress().execution().head();
        return item.preparation()==null?null:item.preparation().binding();
    }
}
