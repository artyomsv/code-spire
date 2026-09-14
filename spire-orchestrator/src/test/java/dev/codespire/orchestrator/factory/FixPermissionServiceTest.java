package dev.codespire.orchestrator.factory;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.ResolvedActor;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.orchestrator.repository.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.codespire.orchestrator.factory.FixAuthorization.Reason.*;

/** Real registry, encrypted selected credential and HTTP adapters; TEST-only Dev Services rows. */
@QuarkusTest
class FixPermissionServiceTest {
    @Test void nativeApprovalRequiresMeasuredPushEvenWithAnAllowOverride() {
        override("900123","ALLOW");response("read");
        var decision=permissions.authorizeApproval(repository,"900123");assertFalse(decision.allowed());assertEquals(CANNOT_PUSH,decision.reason());
        forge.verify(getRequestedFor(urlEqualTo(path())));
    }
    @Test void nativeApprovalStillHonorsDenyDespiteMeasuredPush() {
        response("write");assertTrue(permissions.authorizeApproval(repository,"900123").allowed());override("900123","DENY");
        var decision=permissions.authorizeApproval(repository,"900123");assertFalse(decision.allowed());assertEquals(EXPLICIT_DENY,decision.reason());
    }
    @Inject FixPermissionService permissions;
    @Inject ProviderRegistry providers;
    @Inject RepositoryRegistry repositories;
    @Inject ActorPolicyRegistry actors;
    @Inject DataSource dataSource;
    static WireMockServer forge;
    final List<UUID> accountIds=new ArrayList<>();
    UUID account, repository;
    String slug;
    @BeforeAll static void start() { forge=new WireMockServer(WireMockConfiguration.options().dynamicPort());forge.start(); }
    @AfterAll static void stop() { forge.stop(); }
    @BeforeEach void setup() {
        forge.resetAll();account=account("REVIEWER");slug="TEST-"+UUID.randomUUID();
        repository=repositories.create(new RepositoryInput("github",forge.baseUrl(),"TEST-permissions",slug,true,account,null)).id();
        forge.stubFor(get(urlEqualTo("/user/900123")).willReturn(okJson("{\"id\":900123,\"login\":\"TEST-person\"}")));
        response("write");
    }
    ProviderInput input(String role,boolean enabled,String secret) {
        return new ProviderInput("TEST-permission-"+UUID.randomUUID(),"github",forge.baseUrl(),"bearer",null,secret,
                "TEST-bot-"+role,enabled,List.of(),"TEST-bot-"+role,null,role);
    }
    UUID account(String role) { UUID id=UUID.fromString(providers.create(input(role,true,"TEST-token-"+role)).id());accountIds.add(id);return id; }
    String path() { return "/repos/TEST-permissions/"+slug+"/collaborators/TEST-person/permission"; }
    void response(String role) { forge.stubFor(get(urlEqualTo(path())).willReturn(okJson("{\"permission\":\""+role+"\",\"user\":{\"id\":900123}}"))); }
    void override(String id,String effect) {
        long revision=actors.repository(repository).stream().filter(actor->id.equals(actor.providerUserId())).mapToLong(ActorDisplay::revision).findFirst().orElse(0);
        actors.saveRepository(repository,account,repositories.get(repository).orElseThrow().revision(),new ResolvedActor(id,"TEST-person","TEST Person"),effect,revision);
    }
    FixAuthorization.Decision result() { return permissions.authorize(repository,"900123"); }
    void reason(FixAuthorization.Reason reason,boolean allowed) { FixAuthorization.Decision result=result();assertEquals(reason,result.reason());assertEquals(allowed,result.allowed()); }
    @Test void onlySelectedReviewerCanSupplyPermission() {
        UUID factory=account("FACTORY");RepositoryView view=repositories.get(repository).orElseThrow();
        repositories.update(repository,view.revision(),new RepositoryInput("github",forge.baseUrl(),"TEST-permissions",slug,true,account,factory));
        reason(CAN_PUSH,true);
        forge.verify(getRequestedFor(urlEqualTo(path())).withHeader("Authorization",equalTo("Bearer TEST-token-REVIEWER")));
        forge.verify(0,getRequestedFor(urlMatching(".*")).withHeader("Authorization",equalTo("Bearer TEST-token-FACTORY")));
    }
    @Test void unavailableReviewerCannotBorrowFactoryAuthority() {
        UUID factory=account("FACTORY");RepositoryView view=repositories.get(repository).orElseThrow();
        repositories.update(repository,view.revision(),new RepositoryInput("github",forge.baseUrl(),"TEST-permissions",slug,true,account,factory));
        forge.stubFor(get(urlEqualTo(path())).withHeader("Authorization",equalTo("Bearer TEST-token-REVIEWER")).willReturn(aResponse().withStatus(403)));
        reason(PERMISSION_UNAVAILABLE,false);
        forge.verify(0,getRequestedFor(urlMatching(".*")).withHeader("Authorization",equalTo("Bearer TEST-token-FACTORY")));
    }
    @Test void denyThenAllowThenFreshPermission() {
        override("900123","DENY");reason(EXPLICIT_DENY,false);forge.verify(0,getRequestedFor(urlMatching(".*")));
        override("900123","ALLOW");response("read");reason(EXPLICIT_ALLOW,true);forge.verify(0,getRequestedFor(urlMatching(".*")));
        actors.deleteRepository(repository,"900123",actors.repository(repository).getFirst().revision());
        reason(CANNOT_PUSH,false);response("write");reason(CAN_PUSH,true);
    }
    @Test void anotherActorsOverrideCannotGrant() { override("900999","ALLOW");response("read");reason(CANNOT_PUSH,false); }
    @Test void missingActorCannotUseAnOverride() { override("900123","ALLOW");assertEquals(UNKNOWN_ACTOR,permissions.authorize(null," ").reason());forge.verify(0,getRequestedFor(urlMatching(".*"))); }
    @Test void nullActorIsRefusedBeforeRepositoryResolution() { assertEquals(UNKNOWN_ACTOR,permissions.authorize(null,null).reason());forge.verify(0,getRequestedFor(urlMatching(".*"))); }
    @Test void missingRepositoryCannotGrant() { assertEquals(REPOSITORY_UNAVAILABLE,permissions.authorize(null,"900123").reason());forge.verify(0,getRequestedFor(urlMatching(".*"))); }
    @Test void deletedRepositoryCannotGrant() { assertEquals(REPOSITORY_UNAVAILABLE,permissions.authorize(UUID.randomUUID(),"900123").reason());forge.verify(0,getRequestedFor(urlMatching(".*"))); }
    @Test void disabledRepositoryCannotUseAnOverride() {
        override("900123","ALLOW");RepositoryView view=repositories.get(repository).orElseThrow();
        repositories.update(repository,view.revision(),new RepositoryInput("github",forge.baseUrl(),"TEST-permissions",slug,false,account,null));reason(REPOSITORY_UNAVAILABLE,false);
    }
    @Test void disabledReviewerCannotUseAnOverride() { override("900123","ALLOW");providers.update(account,input("REVIEWER",false,null));reason(REPOSITORY_UNAVAILABLE,false); }
    @Test void previousSuccessCannotAuthorizeDuringAnOutage() { reason(CAN_PUSH,true);forge.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(503)));reason(PERMISSION_UNAVAILABLE,false); }
    @Test void accountRotationCannotSplitAPermissionDecision() throws Exception {
        forge.stubFor(get(urlEqualTo(path())).willReturn(okJson("{\"permission\":\"write\",\"user\":{\"id\":900123}}").withFixedDelay(3000)));
        try (ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<FixAuthorization.Decision> decision=pool.submit(this::result);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty() && System.nanoTime()<deadline) Thread.sleep(20);
            assertFalse(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty(),"lookup must have reached the delayed permission read");
            Future<?> rotation=pool.submit(()->providers.update(account,input("REVIEWER",true,"TEST-rotated")));
            assertDoesNotThrow(()->rotation.get(1,TimeUnit.SECONDS),"an account save must finish while the forge read is outstanding");
            FixAuthorization.Decision result=decision.get(10,TimeUnit.SECONDS);
            assertFalse(result.allowed());assertEquals(PERMISSION_UNAVAILABLE,result.reason());
        }
        forge.resetRequests();reason(CAN_PUSH,true);
        forge.verify(getRequestedFor(urlEqualTo(path())).withHeader("Authorization",equalTo("Bearer TEST-rotated")));
    }
    @Test void repositoryRebindingCannotSplitAPermissionDecision() throws Exception {
        UUID replacement=account("REVIEWER");RepositoryView view=repositories.get(repository).orElseThrow();
        forge.stubFor(get(urlEqualTo(path())).willReturn(okJson("{\"permission\":\"write\",\"user\":{\"id\":900123}}").withFixedDelay(3000)));
        try (ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<FixAuthorization.Decision> decision=pool.submit(this::result);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty() && System.nanoTime()<deadline) Thread.sleep(20);
            assertFalse(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty(),"lookup must have reached the delayed permission read");
            Future<?> rotation=pool.submit(()->repositories.update(repository,view.revision(),new RepositoryInput("github",forge.baseUrl(),"TEST-permissions",slug,true,replacement,null)));
            assertDoesNotThrow(()->rotation.get(1,TimeUnit.SECONDS),"a repository save must finish while the forge read is outstanding");
            FixAuthorization.Decision result=decision.get(10,TimeUnit.SECONDS);
            assertFalse(result.allowed());assertEquals(PERMISSION_UNAVAILABLE,result.reason());
        }
        assertEquals(replacement,repositories.get(repository).orElseThrow().reviewer().id());
    }
    @Test void timedOutPermissionCannotGrant() {
        forge.stubFor(get(urlEqualTo(path())).willReturn(okJson("{\"permission\":\"write\",\"user\":{\"id\":900123}}").withFixedDelay(11000)));
        reason(PERMISSION_UNAVAILABLE,false);
    }
    @Test void aDenyAddedDuringPermissionReadCannotBeMissed() throws Exception {
        forge.stubFor(get(urlEqualTo(path())).willReturn(okJson("{\"permission\":\"write\",\"user\":{\"id\":900123}}").withFixedDelay(3000)));
        try (ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<FixAuthorization.Decision> decision=pool.submit(this::result);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty() && System.nanoTime()<deadline) Thread.sleep(20);
            assertFalse(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty());
            Future<?> change=pool.submit(()->override("900123","DENY"));
            assertDoesNotThrow(()->change.get(1,TimeUnit.SECONDS));
            FixAuthorization.Decision result=decision.get(10,TimeUnit.SECONDS);
            assertFalse(result.allowed());assertEquals(PERMISSION_UNAVAILABLE,result.reason());
        }
        reason(EXPLICIT_DENY,false);
    }
    @Test void aRepositoryDisabledDuringPermissionReadCannotGrant() throws Exception {
        RepositoryView view=repositories.get(repository).orElseThrow();
        forge.stubFor(get(urlEqualTo(path())).willReturn(okJson("{\"permission\":\"write\",\"user\":{\"id\":900123}}").withFixedDelay(3000)));
        try (ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<FixAuthorization.Decision> decision=pool.submit(this::result);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty() && System.nanoTime()<deadline) Thread.sleep(20);
            assertFalse(forge.findAll(getRequestedFor(urlEqualTo(path()))).isEmpty());
            Future<?> change=pool.submit(()->repositories.update(repository,view.revision(),
                    new RepositoryInput("github",forge.baseUrl(),"TEST-permissions",slug,false,account,null)));
            assertDoesNotThrow(()->change.get(1,TimeUnit.SECONDS));
            FixAuthorization.Decision result=decision.get(10,TimeUnit.SECONDS);
            assertFalse(result.allowed());assertEquals(PERMISSION_UNAVAILABLE,result.reason());
        }
        reason(REPOSITORY_UNAVAILABLE,false);
    }
    @AfterEach void cleanup() throws Exception {
        execute("DELETE FROM repository_account WHERE repository_id=?",repository);
        execute("DELETE FROM repository WHERE id=?",repository);
        for(UUID id:accountIds)execute("DELETE FROM scm_provider WHERE id=?",id);
    }
    void execute(String sql,UUID id) throws Exception { try(var c=dataSource.getConnection();var ps=c.prepareStatement(sql)){ps.setObject(1,id);ps.executeUpdate();} }
}
