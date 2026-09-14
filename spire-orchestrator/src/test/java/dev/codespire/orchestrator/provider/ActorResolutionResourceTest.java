package dev.codespire.orchestrator.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.orchestrator.repository.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user="TEST-admin", roles={"spire-admin","spire-viewer"})
class ActorResolutionResourceTest {
    @Inject DataSource dataSource;
    @Inject ProviderRegistry providers;
    @Inject RepositoryRegistry repositories;
    @Inject ActorPolicyRegistry policies;
    static WireMockServer forge;
    final List<UUID> accounts = new ArrayList<>();
    UUID account, repository;
    @BeforeAll static void start() { forge = new WireMockServer(WireMockConfiguration.options().dynamicPort()); forge.start(); }
    @AfterAll static void stop() { forge.stop(); }
    @BeforeEach void setup() {
        forge.resetAll(); account = account("github", "TEST-selected");
        repository = repositories.create(new RepositoryInput("github",forge.baseUrl(),"TEST-actors","TEST-"+UUID.randomUUID(),true,account,null)).id();
        github("TEST-person", "900123");
    }
    UUID account(String type, String secret) {
        UUID id = UUID.fromString(providers.create(new ProviderInput("TEST-actors-"+UUID.randomUUID(),type,forge.baseUrl()+(type.equals("gitlab")?"/api/v4":""),"bearer",null,secret,
                "TEST-bot",true,List.of(),"TEST-bot",null,"REVIEWER")).id());
        accounts.add(id); return id;
    }
    void github(String handle, String id) {
        String json = "{\"id\":"+id+",\"login\":\""+handle+"\",\"name\":\"TEST Person\"}";
        forge.stubFor(get(urlEqualTo("/users/"+handle)).willReturn(okJson(json)));
        forge.stubFor(get(urlEqualTo("/user/"+id)).willReturn(okJson(json)));
    }
    String path() { return "/api/providers/"+account+"/actors"; }
    Map<String,Object> input(String handle,String id,long revision) { return Map.of("handle",handle,"providerUserId",id,"revision",revision); }

    @Test void handleEntryStoresStableIdAndReturnsHandle() throws Exception {
        given().contentType("application/json").body(Map.of("handle","@TEST-person")).post(path()+"/resolve").then().statusCode(200)
                .body("actors[0].providerUserId",org.hamcrest.Matchers.equalTo("900123")).body("actors[0].handle",org.hamcrest.Matchers.equalTo("TEST-person"));
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        try (var c=dataSource.getConnection();var ps=c.prepareStatement("SELECT author,observed_handle FROM provider_author WHERE provider_id=?")) {
            ps.setObject(1,account);try(var rs=ps.executeQuery()) { assertTrue(rs.next());assertEquals("900123",rs.getString(1));assertEquals("TEST-person",rs.getString(2));assertFalse(rs.next()); }
        }
        assertEquals("900123|TEST-person", restartedReader());
        assertEquals("900123|TEST-person", restartedReader(), "A second fresh JVM rehydrates after the first process exits");
        given().get(path()).then().statusCode(200).body("actors[0].handle",org.hamcrest.Matchers.equalTo("TEST-person"));
    }
    @Test void unresolvedHandleIsRejectedWithoutWriting() {
        forge.stubFor(get(urlEqualTo("/users/TEST-missing")).willReturn(aResponse().withStatus(404)));
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        ActorPolicyRegistry.Policy before=policies.account(account);
        given().contentType("application/json").body(input("@TEST-missing","TEST-missing",2)).post(path()).then().statusCode(422);
        assertEquals(before,policies.account(account));
    }
    @Test void usesOnlyTheSelectedAccountsCredential() {
        UUID decoy=account("github","TEST-decoy");
        forge.stubFor(get(urlEqualTo("/users/TEST-person")).withHeader("Authorization",equalTo("Bearer TEST-decoy"))
                .willReturn(okJson("{\"id\":900999,\"login\":\"TEST-person\"}")));
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        assertEquals("900123",policies.account(account).actors().getFirst().providerUserId());
        assertTrue(policies.account(decoy).actors().isEmpty());
        forge.verify(0,getRequestedFor(urlMatching(".*")).withHeader("Authorization",equalTo("Bearer TEST-decoy")));
        forge.verify(getRequestedFor(urlEqualTo("/user/900123")).withHeader("Authorization",equalTo("Bearer TEST-selected")));
    }
    @Test void refusesAnAmbiguousMatch() {
        account=account("gitlab","TEST-gitlab");
        forge.stubFor(get(urlEqualTo("/api/v4/users?username=TEST-person&per_page=100")).willReturn(okJson(
                "[{\"id\":900123,\"username\":\"TEST-person\"},{\"id\":900456,\"username\":\"TEST-person\"}]")));
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(422)
                .body("error",containsString("more than one exact identity"));
        assertTrue(policies.account(account).actors().isEmpty());
    }
    @Test void rechecksSubmittedIdentityOnSave() {
        given().contentType("application/json").body(Map.of("handle","@TEST-person")).post(path()+"/resolve").then().statusCode(200);
        github("TEST-person","900456");
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(422);
        assertTrue(policies.account(account).actors().isEmpty());
    }
    @Test void unavailableDirectoryWritesNothing() {
        forge.stubFor(get(urlEqualTo("/users/TEST-person")).willReturn(aResponse().withStatus(429)));
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(503);
        assertTrue(policies.account(account).actors().isEmpty());
    }
    @Test void failedRefreshRemainsStaleAfterReload() {
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        forge.stubFor(get(urlEqualTo("/user/900123")).willReturn(aResponse().withStatus(503)));
        given().get(path()+"?refresh=true").then().statusCode(200).body("actors[0].stale",org.hamcrest.Matchers.equalTo(true));
        given().get(path()).then().statusCode(200).body("actors[0].stale",org.hamcrest.Matchers.equalTo(true))
                .body("actors[0].handle",org.hamcrest.Matchers.equalTo("TEST-person"));
        assertEquals("900123",policies.account(account).actors().getFirst().providerUserId());
        assertTrue(providers.get(account).orElseThrow().actorDisplays().getFirst().stale());
        github("TEST-renamed","900123");
        given().get(path()+"?refresh=true").then().statusCode(200).body("actors[0].stale",org.hamcrest.Matchers.equalTo(false))
                .body("actors[0].handle",org.hamcrest.Matchers.equalTo("TEST-renamed"));
        assertFalse(policies.account(account).actors().getFirst().stale());
    }
    @Test void unresolvedAndExpiredObservationsAreLabelledStale() throws Exception {
        execute("INSERT INTO provider_author(provider_id,author) VALUES (?,'TEST-unresolved')",account);
        execute("INSERT INTO provider_author(provider_id,author,observed_handle,resolved_at) VALUES (?,'900456','TEST-old',now()-interval '2 days')",account);
        given().get(path()).then().statusCode(200)
                .body("actors.find { it.providerUserId == 'TEST-unresolved' }.stale",org.hamcrest.Matchers.equalTo(true))
                .body("actors.find { it.providerUserId == '900456' }.stale",org.hamcrest.Matchers.equalTo(true));
        forge.verify(0,getRequestedFor(urlMatching(".*")));
    }
    @Test void staleAccountPolicyCannotOverwritePeople() {
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        github("TEST-other","900456");
        given().contentType("application/json").body(input("@TEST-other","900456",1)).post(path()).then().statusCode(409);
        assertEquals(List.of("900123"),policies.account(account).actors().stream().map(ActorDisplay::providerUserId).toList());
    }
    @Test void policyPeoplePreventChangingTheAccountsForge() throws Exception {
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        execute("DELETE FROM repository_account WHERE repository_id=?",repository);
        var changed=new ProviderInput("TEST-account","github","https://TEST-other.example.test","bearer",null,null,
                "TEST-bot",true,null,"TEST-bot",null,"REVIEWER");
        assertThrows(ProviderRegistry.AccountConflict.class,()->providers.update(account,changed));
        assertEquals(forge.baseUrl(),providers.get(account).orElseThrow().baseUrl());
        var otherKind=new ProviderInput("TEST-account","gitlab",forge.baseUrl(),"bearer",null,null,
                "TEST-bot",true,null,"TEST-bot",null,"REVIEWER");
        assertThrows(ProviderRegistry.AccountConflict.class,()->providers.update(account,otherKind));
        policies.deleteAccount(account,"900123",2);
        assertDoesNotThrow(()->providers.update(account,otherKind));
        assertEquals("gitlab",providers.get(account).orElseThrow().type());
    }
    @Test void ordinaryAccountEditsPreserveResolvedPeopleAndTheirObservations() {
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        ActorPolicyRegistry.Policy before=policies.account(account);
        for (List<String> authors:java.util.Arrays.asList(null,List.of("900123"))) {
            var body=new ProviderInput("TEST-renamed-account","github",forge.baseUrl(),"bearer",null,null,
                    "TEST-bot",true,authors,"TEST-bot",null,"REVIEWER");
            given().contentType("application/json").body(body).put("/api/providers/"+account).then().statusCode(200);
            assertEquals(before,policies.account(account));
        }
    }
    @Test void accountTokenValidationCannotOverwriteANewerPolicy() throws Exception {
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        var before=providers.get(account).orElseThrow();
        String secretBefore=providers.resolveById(account).orElseThrow().secret();
        var body=new ProviderInput("TEST-renamed-account","github",forge.baseUrl(),"bearer",null,"TEST-rotated",
                "TEST-bot",true,List.of("900123"),"TEST-bot",null,"REVIEWER");
        forge.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":900999,\"login\":\"TEST-bot\"}").withFixedDelay(2000)));
        ClassLoader testLoader=Thread.currentThread().getContextClassLoader();
        var request=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            Thread thread=Thread.currentThread();ClassLoader previous=thread.getContextClassLoader();
            try { thread.setContextClassLoader(testLoader);return given().contentType("application/json").body(body).put("/api/providers/"+account).statusCode(); }
            finally { thread.setContextClassLoader(previous); }
        });
        try {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->
                    forge.countRequestsMatching(getRequestedFor(urlEqualTo("/user")).build()).getCount()>0);
            github("TEST-other","900456");
            given().contentType("application/json").body(input("@TEST-other","900456",2)).post(path()).then().statusCode(200);
            var edited=policies.account(account);
            assertEquals(2,edited.actors().size());
            assertEquals(409,request.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(edited,policies.account(account),"The newer people, observations and revision must survive");
            assertEquals(before.name(),providers.get(account).orElseThrow().name());
            assertEquals(secretBefore,providers.resolveById(account).orElseThrow().secret());
        } finally { request.get(15,java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test void rawAuthorInputCannotBypassResolution() {
        var changed=new ProviderInput("TEST-account","github",forge.baseUrl(),"bearer",null,null,
                "TEST-bot",true,List.of("TEST-raw-handle"),"TEST-bot",null,"REVIEWER");
        given().contentType("application/json").body(changed).put("/api/providers/"+account).then().statusCode(409);
        assertTrue(policies.account(account).actors().isEmpty());
    }
    @Test void rawAuthorInputCannotCreateAnAccount() {
        var body=new ProviderInput("TEST-raw-create","github",forge.baseUrl(),"bearer",null,"TEST-secret",
                "TEST-bot",true,List.of("TEST-raw-handle"),"TEST-bot",null,"REVIEWER");
        var before=providers.list().stream().map(ProviderView::id).sorted().toList();
        forge.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":900999,\"login\":\"TEST-bot\"}")));
        given().contentType("application/json").body(body).post("/api/providers").then().statusCode(409)
                .body(containsString("Save the account first"));
        assertEquals(before,providers.list().stream().map(ProviderView::id).sorted().toList());
    }
    @Test void staleAccountDeleteCannotRemovePeople() {
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(200);
        given().delete(path()+"/900123?revision=1").then().statusCode(409);
        assertEquals("900123",policies.account(account).actors().getFirst().providerUserId());
        given().delete(path()+"/900123?revision=2").then().statusCode(200);
        assertTrue(policies.account(account).actors().isEmpty());
    }
    @Test void repositoryWithoutReviewerCannotResolvePeople() throws Exception {
        execute("DELETE FROM repository_account WHERE repository_id=?",repository);
        given().contentType("application/json").body(input("@TEST-person","900123",0))
                .post("/api/repositories/"+repository+"/fix-actors/resolve").then().statusCode(409)
                .body("error",containsString("Select a reviewer"));
        forge.verify(0,getRequestedFor(urlMatching(".*")));
    }
    @Test void memberScopeMustBeBoundToTheSelectedAccount() {
        UUID decoy=account("github","TEST-decoy");
        var body=new HashMap<>(input("@TEST-person","900123",1));body.put("repositoryId",repository);
        given().contentType("application/json").body(body).post("/api/providers/"+decoy+"/actors/resolve")
                .then().statusCode(422).body("error",containsString("bound to this account"));
        forge.verify(0,getRequestedFor(urlMatching(".*")));
        given().contentType("application/json").body(body).post(path()+"/resolve").then().statusCode(200);
    }
    @Test void invalidOverrideEffectIsRejectedBeforeLookup() {
        var body=new HashMap<>(input("@TEST-person","900123",0));body.put("effect","OTHER");
        given().contentType("application/json").body(body).post("/api/repositories/"+repository+"/fix-actors")
                .then().statusCode(422).body("error",containsString("Choose Allow or Deny"));
        assertTrue(policies.repository(repository).isEmpty());forge.verify(0,getRequestedFor(urlMatching(".*")));
    }
    @Test void repositoryRebindOrRevisionChangeInvalidatesResolution() {
        var actor=new dev.codespire.contract.scm.ResolvedActor("900123","TEST-person","TEST Person");
        var repo=repositories.get(repository).orElseThrow();
        assertThrows(ActorPolicyRegistry.Conflict.class,()->policies.saveRepository(repository,UUID.randomUUID(),repo.revision(),actor,"ALLOW",0));
        assertThrows(ActorPolicyRegistry.Conflict.class,()->policies.saveRepository(repository,account,repo.revision()-1,actor,"ALLOW",0));
        assertTrue(policies.repository(repository).isEmpty());
        policies.saveRepository(repository,account,repo.revision(),actor,"ALLOW",0);
        assertEquals("900123",policies.repository(repository).getFirst().providerUserId());
    }
    @Test void accountEditWaitsForTheIdentityLookupAndWrite() throws Exception { assertLookupHoldsLock(false); }
    @Test void repositoryEditWaitsForTheIdentityLookupAndWrite() throws Exception { assertLookupHoldsLock(true); }
    void assertLookupHoldsLock(boolean repositoryPolicy) throws Exception {
        forge.stubFor(get(urlEqualTo("/users/TEST-person")).willReturn(okJson("{\"id\":900123,\"login\":\"TEST-person\"}").withFixedDelay(2000)));
        String url=repositoryPolicy?"/api/repositories/"+repository+"/fix-actors":path();
        var body=new HashMap<>(input("@TEST-person","900123",repositoryPolicy?0:1));body.put("effect","ALLOW");
        ClassLoader testLoader=Thread.currentThread().getContextClassLoader();
        var request=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            Thread thread=Thread.currentThread();ClassLoader previous=thread.getContextClassLoader();
            // Jackson modules must use the same isolated Quarkus test loader on the request thread.
            try { thread.setContextClassLoader(testLoader);return given().contentType("application/json").body(body).post(url).statusCode(); }
            finally { thread.setContextClassLoader(previous); }
        });
        try {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->
                    forge.countRequestsMatching(getRequestedFor(urlEqualTo("/users/TEST-person")).build()).getCount()>0);
            try(var c=dataSource.getConnection();var statement=c.createStatement()) {
                statement.execute("SET lock_timeout='500ms'");
                String sql=repositoryPolicy?"UPDATE repository SET revision=revision WHERE id=?":"UPDATE scm_provider SET name=name WHERE id=?";
                try(var update=c.prepareStatement(sql)) {
                    update.setObject(1,repositoryPolicy?repository:account);
                    var failure=assertThrows(java.sql.SQLException.class,update::executeUpdate);
                    assertEquals("55P03",failure.getSQLState(),"The competing editor must wait on the policy transaction's lock");
                } finally { statement.execute("SET lock_timeout='0'"); }
            }
        } finally { assertEquals(200,request.get(15,java.util.concurrent.TimeUnit.SECONDS)); }
    }
    @Test void repositoryRefreshFailurePersistsUntilSuccessfulIdRefresh() {
        var body=new HashMap<>(input("@TEST-person","900123",0));body.put("effect","DENY");
        String url="/api/repositories/"+repository+"/fix-actors";
        given().contentType("application/json").body(body).post(url).then().statusCode(200);
        forge.stubFor(get(urlEqualTo("/user/900123")).willReturn(aResponse().withStatus(503)));
        given().get(url+"?refresh=true").then().statusCode(200).body("[0].stale",org.hamcrest.Matchers.equalTo(true));
        given().get(url).then().statusCode(200).body("[0].stale",org.hamcrest.Matchers.equalTo(true));
        github("TEST-renamed","900123");
        given().get(url+"?refresh=true").then().statusCode(200).body("[0].handle",org.hamcrest.Matchers.equalTo("TEST-renamed"));
        ActorDisplay after=policies.repository(repository).getFirst();
        assertFalse(after.stale());assertEquals("900123",after.providerUserId());assertEquals("DENY",after.effect());
    }
    @Test void staleDeleteCannotRemoveANewerOverride() {
        String url="/api/repositories/"+repository+"/fix-actors";
        Map<String,Object> body=new HashMap<>(input("@TEST-person","900123",0));body.put("effect","ALLOW");
        long revision=((Number)given().contentType("application/json").body(body).post(url).then().statusCode(200).extract().path("[0].revision")).longValue();
        body.put("effect","DENY");body.put("revision",revision);
        given().contentType("application/json").body(body).post(url).then().statusCode(200);
        given().delete(url+"/900123?revision="+revision).then().statusCode(409);
        assertEquals("DENY",policies.repository(repository).getFirst().effect());
    }
    @Test void repositoryOverridesAreBidirectionalAndRejectStaleEdits() {
        String url="/api/repositories/"+repository+"/fix-actors";
        Map<String,Object> body=new HashMap<>(input("@TEST-person","900123",0));body.put("effect","ALLOW");
        long revision=((Number)given().contentType("application/json").body(body).post(url).then().statusCode(200).extract().path("[0].revision")).longValue();
        body.put("effect","DENY");body.put("revision",revision);
        given().contentType("application/json").body(body).post(url).then().statusCode(200).body("[0].effect",org.hamcrest.Matchers.equalTo("DENY"));
        body.put("effect","ALLOW");
        given().contentType("application/json").body(body).post(url).then().statusCode(409);
        assertEquals("DENY",policies.repository(repository).getFirst().effect());
        assertEquals(1,policies.repository(repository).size());
    }
    @Test void disabledAccountCannotResolvePeople() throws Exception {
        execute("UPDATE scm_provider SET enabled=false WHERE id=?",account);
        given().contentType("application/json").body(input("@TEST-person","900123",1)).post(path()).then().statusCode(409);
        forge.verify(0,getRequestedFor(urlMatching(".*")));
    }
    @Test @TestSecurity(user="TEST-viewer",roles="spire-viewer") void viewerCannotResolveOrEditPeople() {
        policies.saveAccount(account,new dev.codespire.contract.scm.ResolvedActor("900123","TEST-person","TEST Person"),1);
        var before=policies.account(account);
        given().contentType("application/json").body(input("@TEST-person","900123",2)).post(path()).then().statusCode(403);
        given().contentType("application/json").body(input("@TEST-person","900123",2)).post(path()+"/resolve").then().statusCode(403);
        given().get(path()).then().statusCode(403);
        given().delete(path()+"/900123?revision=2").then().statusCode(403);
        given().contentType("application/json").body(input("@TEST-person","900123",0))
                .post("/api/repositories/"+repository+"/fix-actors/resolve").then().statusCode(403);
        assertEquals(before,policies.account(account));
    }
    String restartedReader() throws Exception {
        String classpath=java.util.stream.Stream.of(ActorPolicyRestartProbe.class,ActorPolicyRegistry.class,org.postgresql.ds.PGSimpleDataSource.class,
                jakarta.transaction.Transactional.class,jakarta.inject.Inject.class,jakarta.enterprise.context.ApplicationScoped.class)
                .map(type -> { try { return java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch(Exception e) { throw new IllegalStateException(e); } }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Process process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,ActorPolicyRestartProbe.class.getName()).redirectErrorStream(true).start();
        var config=org.eclipse.microprofile.config.ConfigProvider.getConfig();
        try(var writer=new java.io.PrintWriter(process.getOutputStream())) {
            writer.println(config.getValue("quarkus.datasource.jdbc.url",String.class));writer.println(config.getValue("quarkus.datasource.username",String.class));
            writer.println(config.getValue("quarkus.datasource.password",String.class));writer.println(account);
        }
        assertTrue(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
        assertEquals(0,process.exitValue(),output);return output;
    }
    void execute(String sql,Object...args) throws Exception {
        try(var c=dataSource.getConnection();var ps=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);ps.executeUpdate(); }
    }
    @AfterEach void cleanup() throws Exception {
        execute("DELETE FROM scm_provider WHERE name='TEST-raw-create'");
        execute("DELETE FROM repository_account WHERE repository_id=?",repository);
        execute("DELETE FROM repository WHERE id=?",repository);
        for(UUID id:accounts)execute("DELETE FROM scm_provider WHERE id=?",id);
    }
}
