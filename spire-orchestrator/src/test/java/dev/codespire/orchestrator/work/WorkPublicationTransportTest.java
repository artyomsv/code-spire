package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkPublicationTransportTest {
    final WorkPublicationTransport transport=new WorkPublicationTransport();
    final List<RunCommand> commands=new ArrayList<>();
    IllegalStateException failure;
    WorkPublicationTransportTest(){transport.emitter=new RunCommandEmitter(){
        @Override public void dispatch(RunCommand command){throw new AssertionError("Publication must use control, never paid execution");}
        @Override public void control(RunCommand command){commands.add(command);if(failure!=null)throw failure;}
    };}
    RunCommand.PublishWorkRun permit(){return new RunCommand.PublishWorkRun("TEST-run",new WorkPublicationPermit(new WorkRunBinding("TEST-item",1,UUID.randomUUID(),"a".repeat(64)),
            UUID.randomUUID(),"b".repeat(40),Instant.EPOCH,Instant.EPOCH.plusSeconds(30),List.of()),"TEST-scm-ciphertext");}
    @Test void publicationUsesOnlyTheExistingRunsControlChannel(){var permit=permit();assertInstanceOf(RunLaunch.Dispatched.class,transport.publish(permit));assertEquals(List.of(permit),commands);}
    @Test void anUnclassifiedBrokerFailureStaysUncertain(){failure=new IllegalStateException("TEST missing acknowledgement");assertInstanceOf(RunLaunch.Uncertain.class,transport.publish(permit()));}
    @Test void aDefiniteSerializationMissCanBeRetried(){failure=BrokerAckFailure.rejected("TEST serialization",new org.apache.kafka.common.errors.SerializationException("TEST unsupported record"));assertInstanceOf(RunLaunch.DefiniteMiss.class,transport.publish(permit()));}
    @Test void aLostBrokerAcknowledgementStaysUncertain(){failure=BrokerAckFailure.notAcknowledged("TEST timeout",new java.util.concurrent.TimeoutException("TEST timed out"));assertInstanceOf(RunLaunch.Uncertain.class,transport.publish(permit()));}
    @Test void cancellationUsesTheSameControlChannel(){transport.cancel("TEST-run");var cancel=assertInstanceOf(RunCommand.CancelRun.class,commands.getFirst());assertEquals("TEST-run",cancel.runId());}
}
