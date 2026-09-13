package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestSecurity(user="TEST-recovery-admin",roles="spire-admin")
class GateProcessRecoveryIT extends WorkFixture {
    @TempDir Path temporary;
    final List<Process> children=new ArrayList<>();
    @Test void restartExpiresOpenGateAndReleasesReservation() throws Exception {
        UUID gated=UUID.randomUUID();extraProfiles.add(gated);
        var modes=new EnumMap<WorkPolicy.Phase,String>(WorkPolicy.Phase.class);modes.put(WorkPolicy.Phase.INTAKE,"auto");modes.put(WorkPolicy.Phase.SPEC,"approve");
        policies.createVersion(new WorkPolicy.Profile(gated,"TEST-restart-gate-"+gated,1,1_000_000_301,modes,
                new WorkPolicyLimits(20,5,20,7200,2_000_000,40,Set.of())));
        var pin=new WorkPolicyRegistry.Pin(gated,1);policies.save(repository,new WorkPolicyRegistry.Input(1,pin,Map.of(LABEL,pin)));
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(ticket()).toString())));
        Process first=start("TEST-gate-first",Map.of("spire.work-gate-expiry-interval","off"));
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()-> {
            assertTrue(first.isAlive());assertNotNull(store.load(itemId));assertNotNull(store.load(itemId).gate());assertEquals("OPEN",store.load(itemId).gate().state());
        });
        WorkGate gate=store.load(itemId).gate();
        assertIncomingKafkaIsolation("TEST-gate-first");
        given().get("/api/approvals").then().statusCode(200).body("gate.id",hasItem(gate.id().toString()));
        assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=? AND slot_reserved",itemId));
        kill(first);assertTrue(Instant.now().isBefore(gate.expiresAt()),"Kill must occur while the approval is still live");
        Process second=start("TEST-gate-second",Map.of("spire.work-scan-interval","off","spire.work-gate-expiry-interval","0.2s"));
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()-> {
            assertTrue(second.isAlive());assertEquals("EXPIRED",store.load(itemId).gate().state());assertEquals("gate_expired",store.load(itemId).reason());
            assertEquals(0,count("SELECT count(*) FROM work_item WHERE id=? AND slot_reserved",itemId));
        });
        assertIncomingKafkaIsolation("TEST-gate-second");
        kill(second);
        given().get("/api/approvals").then().statusCode(200).body("gate.id",not(hasItem(gate.id().toString())));
        given().get("/api/work-items/"+itemId).then().statusCode(200).body("reason",is("gate_expired"));
        assertEquals(0,count("SELECT count(*) FROM work_phase_attempt WHERE work_item_id=?",itemId));
    }
    Process start(String name,Map<String,String> overrides) throws Exception {
        Process child=WorkProcessHarness.start(temporary,name,overrides);children.add(child);return child;
    }
    void assertIncomingKafkaIsolation(String name) throws Exception {
        Properties child=new Properties();try(var input=Files.newInputStream(temporary.resolve(name+".properties"))){child.load(input);}
        String group=child.getProperty("mp.messaging.incoming.work-integration-in.group.id");
        assertNotNull(group);assertTrue(group.startsWith("TEST-recovery-"));
        var config=org.eclipse.microprofile.config.ConfigProvider.getConfig();
        String parent=config.getValue("mp.messaging.incoming.work-integration-in.group.id",String.class);
        try(var admin=org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers",config.getValue("kafka.bootstrap.servers",String.class)))) {
            await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(()-> {
                var groups=admin.describeConsumerGroups(List.of(parent,group)).all().get(5,TimeUnit.SECONDS);
                assertEquals(1,groups.get(parent).members().size(),"The recovery child must not join the parent's work consumer group");
                assertEquals(1,groups.get(group).members().size());
                var topics=groups.get(group).members().stream().flatMap(member->member.assignment().topicPartitions().stream()).map(org.apache.kafka.common.TopicPartition::topic).toList();
                assertFalse(topics.isEmpty());assertTrue(topics.stream().allMatch(topic->topic.startsWith("TEST-recovery-")),"The child must consume only isolated TEST topics");
            });
        }
    }
    void kill(Process child) throws Exception {child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));}
    @AfterEach void stopChildren() throws Exception {
        for(Process child:children)if(child.isAlive())kill(child);
        Path reports=Path.of(System.getProperty("spire.test.packaged-app")).getParent().getParent().resolve("reports/gate-recovery");Files.createDirectories(reports);
        try(var files=Files.list(temporary)) {for(Path path:files.filter(value->value.getFileName().toString().endsWith(".log")).toList())Files.copy(path,reports.resolve(path.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
    }
}
