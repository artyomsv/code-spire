package dev.codespire.contract.command;

import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecuteRunCheckpointTest {
    RunCommand.ExecuteRun run(){return new RunCommand.ExecuteRun("TEST-run",new RepoRef("TEST-ws","TEST-repo"),"https://forge.example.test/TEST.git",
            "main","a".repeat(40),"spire/work-TEST","TEST prompt","codex","TEST-model","TEST-image",List.of(),60,"TEST-scm","TEST-harness");}
    @Test void everyWitherKeepsTheCheckpoint(){
        var from=run().fromCheckpoint("TEST-previous","b".repeat(40));
        assertEquals("TEST-previous",from.atEffort("high").paidBySignIn().startFromRunId());
        assertEquals("b".repeat(40),from.atEffort("high").startFromHead());
        assertEquals("TEST-previous",from.paidBySignIn().startFromRunId());
    }
    @Test void aCheckpointNeedsBothItsRunAndAFullHead(){
        assertThrows(IllegalArgumentException.class,()->run().fromCheckpoint("TEST-previous","abc"));
        assertThrows(IllegalArgumentException.class,()->run().fromCheckpoint(" ","b".repeat(40)));
        assertThrows(IllegalArgumentException.class,()->run().fromCheckpoint("TEST-previous",null));
    }
    @Test void aRunWithoutACheckpointStartsFromItsBase(){assertNull(run().startFromRunId());assertNull(run().startFromHead());}
}
