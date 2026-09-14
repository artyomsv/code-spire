package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkRunTransportTest {
    @Test void productionDeclaresNoPublicationCapabilityBeforeTheHoldExists(){assertFalse(new WorkRunTransport().available());}
    @Test void anUnavailableTransportCannotReachTheLauncher(){
        List<RunCommand.ExecuteRun> sent=new ArrayList<>();var transport=new WorkRunTransport();
        transport.launch=new RunLaunch(){@Override public Outcome launch(RunCommand.ExecuteRun command){sent.add(command);return new Dispatched();}};
        assertThrows(IllegalStateException.class,()->transport.dispatch(null));assertTrue(sent.isEmpty());
    }
    WorkPhaseCapability capability(){var value=new WorkPhaseCapability();value.runs=new WorkRunTransport(){@Override public boolean available(){return true;}};return value;}
    WorkItemEvent item(boolean prepared){
        var location=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://tracker.example.test","TEST-project","TEST-issue"),"TEST-key",URI.create("https://tracker.example.test/TEST-key"));
        var artifact=new WorkPreparation.Artifact(location,"a".repeat(64));
        return new WorkItemEvent("TEST-item",UUID.randomUUID(),UUID.randomUUID(),location,1,1,null,Map.of(),null,null,"build","awaiting_input","TEST-reason",WorkPolicyLimits.inactive(),"TEST-event",null,WorkProgress.empty(),
                prepared?new WorkPreparation(artifact,artifact,"main","a".repeat(40),"TEST-harness","TEST-model","TEST-operator"):null);
    }
    @Test void aBuildRequiresPreparedArtifactsEvenWhenTransportIsAvailable(){assertFalse(capability().available(item(false),"build"));}
    @Test void aPreparedBuildDoesNotInventAVerifier(){assertFalse(capability().available(item(true),"verify"));assertTrue(capability().available(item(true),"build"));}
}
