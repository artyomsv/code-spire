package dev.codespire.contract.work;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Trusted control-plane decision. Repository and handoff files can never supply this permit. */
public record WorkPublicationPermit(WorkRunBinding work,UUID deliveryAttemptId,String head,
                                    Instant issuedAt,Instant expiresAt,List<String> protectedPaths) {
    public WorkPublicationPermit {
        Objects.requireNonNull(work,"A work binding is required");
        Objects.requireNonNull(deliveryAttemptId,"A delivery attempt is required");
        if(head==null || !head.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("A full checkpoint head is required");
        Objects.requireNonNull(issuedAt,"A permit issue time is required");
        Objects.requireNonNull(expiresAt,"A permit expiry is required");
        if(!expiresAt.isAfter(issuedAt))throw new IllegalArgumentException("A permit needs a positive validity window");
        protectedPaths=List.copyOf(Objects.requireNonNull(protectedPaths,"Current protected paths are required"));
    }
    /** Expired records remain decodable so their refusal can be recorded after restart. */
    public boolean validAt(Instant now) { return !now.isBefore(issuedAt) && now.isBefore(expiresAt); }
}
