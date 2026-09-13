package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkAdmissionPinTest extends WorkFixture {
    @Test void aRemovedRestrictionCannotWidenThePersistedAdmission() throws Exception {
        WorkPolicy.Profile original=policies.profiles().stream().filter(row->row.id().equals(profile)).findFirst().orElseThrow();
        UUID restricting=UUID.randomUUID();extraProfiles.add(restricting);
        Map<WorkPolicy.Phase,String> modes=new EnumMap<>(original.modes());modes.put(WorkPolicy.Phase.PLAN,"approve");
        policies.createVersion(new WorkPolicy.Profile(restricting,"TEST-restricting-"+restricting,1,original.precedence()+1,modes));
        WorkPolicyRegistry.Pin originalPin=new WorkPolicyRegistry.Pin(profile,1),restriction=new WorkPolicyRegistry.Pin(restricting,1);
        policies.save(repository,new WorkPolicyRegistry.Input(1,originalPin,Map.of(LABEL,originalPin,"TEST-limit",restriction)));
        var both=ticket();both.withArray("labels").addObject().put("name","TEST-limit");
        forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(both.toString())));
        var first=mapper.createObjectNode().put("id",101).put("event","labeled").put("created_at","2026-09-13T12:00:00Z");
        first.putObject("actor").put("id",900123);first.putObject("label").put("name",LABEL);
        var second=first.deepCopy().put("id",102);second.putObject("label").put("name","TEST-limit");
        forge.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1")).willReturn(okJson(mapper.createArrayNode().add(first).add(second).toString())));
        assertEquals(itemId,intake.accept(signed("900123")));
        WorkItemEvent admitted=store.load(itemId);
        assertEquals("approve",admitted.admittedModes().get(WorkPolicy.Phase.PLAN));assertEquals(2,admitted.policy().applied().size());
        assertEquals(account,admitted.authority().accountId());assertEquals(2,admitted.authority().sourceRevision());
        forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString())));audit("900123");
        assertEquals(itemId,intake.reconcile(sources.get(source).orElseThrow(),issue,null,"TEST-label-removed"));
        WorkItemEvent later=store.load(itemId);
        assertEquals("approve",later.policy().effective().get(WorkPolicy.Phase.PLAN));
        assertEquals(admitted.admittedModes(),later.admittedModes());assertEquals(1,later.policy().applied().size());
        assertEquals(List.of("POLICY_CLAMPED","POLICY_OBSERVED","POLICY_OBSERVED"),
                store.history(itemId).stream().map(event->((WorkItemEvent)event.payload()).milestone()).toList(),
                "The admission clamp and both policy observations must remain distinct durable facts");
    }
}
