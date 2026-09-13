package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.scm.*;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.orchestrator.readmodel.*;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real repository-aware ingress, including the outer legacy gate and the finding/dispatch path. */
class FixPermissionSagaTest {
    private static final String ACTOR="900123";
    private boolean observeOnly;
    private final List<String> events=new ArrayList<>();
    private final List<String> decisions=new ArrayList<>();
    private final List<String> dispatches=new ArrayList<>();
    private String actorId=ACTOR;
    private List<String> legacyAuthors=List.of();
    private RepositoryPermission.State permission=RepositoryPermission.State.CAN_PUSH;
    private FixAuthorization.Override override;

    @Test void writerWithEmptyOverridesDispatches() { command(); assertOutcome(true,FixAuthorization.Reason.CAN_PUSH); }
    @Test void writerOutsideTheLegacyAuthorListDispatches() {
        legacyAuthors=List.of("900999"); command(); assertOutcome(true,FixAuthorization.Reason.CAN_PUSH);
    }
    @Test void readerWithoutOverrideIsRefused() {
        permission=RepositoryPermission.State.CANNOT_PUSH; command(); assertOutcome(false,FixAuthorization.Reason.CANNOT_PUSH);
    }
    @Test void explicitGrantLetsAReaderDispatch() {
        permission=RepositoryPermission.State.CANNOT_PUSH; override=FixAuthorization.Override.ALLOW;
        command(); assertOutcome(true,FixAuthorization.Reason.EXPLICIT_ALLOW);
    }
    @Test void explicitDenyStopsAWriter() {
        legacyAuthors=List.of(ACTOR); override=FixAuthorization.Override.DENY;
        command(); assertOutcome(false,FixAuthorization.Reason.EXPLICIT_DENY);
    }
    @Test void unknownPermissionCannotDispatch() {
        permission=RepositoryPermission.State.UNKNOWN; command(); assertOutcome(false,FixAuthorization.Reason.PERMISSION_UNAVAILABLE);
    }
    private String thisActor() { return actorId; }
    @Test void unknownActorCannotUseAnExplicitGrant() {
        actorId=" ";override=FixAuthorization.Override.ALLOW;command();assertOutcome(false,FixAuthorization.Reason.UNKNOWN_ACTOR);
    }
    @Test void nullActorCannotUseAnExplicitGrant() {
        actorId=null;override=FixAuthorization.Override.ALLOW;command();assertOutcome(false,FixAuthorization.Reason.UNKNOWN_ACTOR);
    }
    @Test void selfCommandCannotUseAnExplicitGrant() {
        actorId="900888";override=FixAuthorization.Override.ALLOW;command();
        assertTrue(dispatches.isEmpty());assertTrue(decisions.isEmpty());assertTrue(events.contains("SelfLoopDropped"));
    }
    @Test void explicitGrantCannotBypassObserveMode() {
        observeOnly=true;override=FixAuthorization.Override.ALLOW;command();
        assertTrue(dispatches.isEmpty());assertTrue(decisions.isEmpty());assertTrue(events.contains("ManualCommandObserveOnly"));
    }
    private void assertOutcome(boolean allowed,FixAuthorization.Reason reason) {
        assertEquals(allowed?List.of("TEST-root|TEST-comment|77"):List.of(),dispatches);
        assertEquals(List.of(reason.name()),decisions,"The saga must record the actual permission decision");
    }
    private void command() {
        var saga=new IntegrationSaga();
        saga.timeline=new TimelineBroadcaster() {
            @Override public void record(String lane,String type,String reviewId,String detail) {
                events.add(type);
                if(type.equals("FixAuthorization"))decisions.add(detail);
            }
        };
        saga.projection=new TestRepositoryProjection() {
            @Override public boolean archived(String reviewId){return false;}
            @Override public boolean registered(String reviewId){return true;}
            @Override public void appendEvent(String id,String lane,String type,String detail){}
            @Override public void appendEvent(String id,String lane,String type,String detail,String ref){}
        };
        saga.policy=new ReviewPolicy(){@Override public boolean observeOnly(){return observeOnly;}};
        var provider=new ScmProvider(UUID.fromString("00000000-0000-4000-8000-000000000003"),"TEST-reviewer","github",
                "https://TEST-forge.invalid","bearer",null,"TEST-token","900888",true,legacyAuthors,"TEST-bot",null,ProviderRole.REVIEWER);
        saga.reviewProviders=new ReviewProviderResolver(){@Override public Optional<ScmProvider> resolveForReview(String id){return Optional.of(provider);}};
        saga.repositoryAccounts=new RepositoryAccounts(){@Override public Optional<ScmProvider> resolve(UUID id,ProviderRole role){return Optional.of(provider);}};
        saga.fixPermissions=new FixPermissionService(){
            @Override public FixAuthorization.Decision authorize(UUID repositoryId,String actorId){
                assertEquals(TestRepositoryProjection.REPOSITORY_ID,repositoryId); assertEquals(thisActor(),actorId);
                return FixAuthorization.decide(actorId,override,new RepositoryPermission(permission,"TEST-permission-unavailable"));
            }
        };
        saga.threads=new ReviewThreadView(){@Override public ThreadRef rootOf(String id,ThreadRef thread){return new ThreadRef("TEST-root");}};
        saga.findings=new FindingProjection(){@Override public Optional<TargetFinding> findByThread(String id,String thread){
            assertEquals("TEST-root",thread);return Optional.of(new TargetFinding(77L,2,"TEST-file.java",44,48,"HIGH",null,"review"));
        }};
        saga.fixRuns=new FixRunDispatcher(){@Override public Result dispatch(String id,RepoRef repo,String thread,String comment,FindingProjection.TargetFinding finding){
            dispatches.add(thread+"|"+comment+"|"+finding.id());return new Dispatched("run::TEST-permission:1");
        }};
        var event=new ManualCommandReceived(new RepoRef("TEST-owner","TEST-repo"),900001L,"fix","",
                Author.of(actorId,"TEST-person","TEST Person"),new ThreadRef("TEST-reply"),null,"TEST-comment");
        // on(IntegrationEvent) now refuses legacy provenance; this is the public normalized ingress after slice 2.
        saga.onRepository(event,TestRepositoryProjection.REPOSITORY_ID);
    }
}
