package dev.codespire.contract.work;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted control-plane decision. Repository and handoff files can never supply this permit.
 *
 * <p>{@code expectedRemoteHead} is set only for a land fix (M4 slice 7a): the item's own branch already exists
 * and the push may land only while the remote branch still points there. {@code mergedBaseTip} is the base
 * commit the land effect read for a conflict or behind fix; paths the base brought are not the agent's.
 * It rides here, issued by the orchestrator, because the agent can move any ref in its own clone.
 */
public record WorkPublicationPermit(WorkRunBinding work,UUID deliveryAttemptId,String head,
                                    Instant issuedAt,Instant expiresAt,List<String> protectedPaths,
                                    String expectedRemoteHead,String mergedBaseTip) {
    public WorkPublicationPermit {
        Objects.requireNonNull(work,"A work binding is required");
        Objects.requireNonNull(deliveryAttemptId,"A delivery attempt is required");
        if(head==null || !head.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("A full checkpoint head is required");
        Objects.requireNonNull(issuedAt,"A permit issue time is required");
        Objects.requireNonNull(expiresAt,"A permit expiry is required");
        if(!expiresAt.isAfter(issuedAt))throw new IllegalArgumentException("A permit needs a positive validity window");
        protectedPaths=List.copyOf(Objects.requireNonNull(protectedPaths,"Current protected paths are required"));
        if(expectedRemoteHead!=null && !expectedRemoteHead.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("An expected remote head must be a full sha");
        if(mergedBaseTip!=null && (expectedRemoteHead==null || !mergedBaseTip.matches("[0-9a-f]{40}")))
            throw new IllegalArgumentException("A merged base tip is a full sha, and only for a push to an existing branch");
    }
    public WorkPublicationPermit(WorkRunBinding work,UUID deliveryAttemptId,String head,
                                 Instant issuedAt,Instant expiresAt,List<String> protectedPaths) {
        this(work,deliveryAttemptId,head,issuedAt,expiresAt,protectedPaths,null,null);
    }
    /** The same permit for a push to the item's existing branch; every other component is enumerated once, here. */
    public WorkPublicationPermit onExistingBranch(String expectedRemoteHead,String mergedBaseTip) {
        return new WorkPublicationPermit(work,deliveryAttemptId,head,issuedAt,expiresAt,protectedPaths,expectedRemoteHead,mergedBaseTip);
    }
    public boolean pushesToExistingBranch() { return expectedRemoteHead!=null; }
    /** Expired records remain decodable so their refusal can be recorded after restart. */
    public boolean validAt(Instant now) { return !now.isBefore(issuedAt) && now.isBefore(expiresAt); }
}
