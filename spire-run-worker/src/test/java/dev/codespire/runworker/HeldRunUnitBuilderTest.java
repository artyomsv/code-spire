package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.*;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.*;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HeldRunUnitBuilderTest {
    final ObjectMapper mapper=new ObjectMapper();
    final EncryptionService encryption=new EncryptionService(EncryptionService.generateKeysetBase64());
    final WorkRunBinding binding=new WorkRunBinding("TEST-work",1,UUID.randomUUID(),"a".repeat(64));
    final RunUnitBuilder builder=new RunUnitBuilder();
    HeldRunUnitBuilderTest(){
        builder.credentials=new Credentials();builder.credentials.encryption=encryption;builder.credentials.mapper=mapper;
        builder.enterprise=RunLauncherTest.noCorporateEnvironment();builder.publisherImage="TEST-original-publisher";builder.maxWallClockSeconds=3600;
    }
    String scm(String id,String secret) throws Exception {return encryption.encryptString(mapper.writeValueAsString(new MachineAccountCredential("TEST-bot",secret)),RunCommand.scmCredentialAad(id));}
    RunCommand.ExecuteWorkRun command(String id,String harness) throws Exception {
        return new RunCommand.ExecuteWorkRun(new RunCommand.ExecuteRun(id,new RepoRef("TEST-work","app"),"https://forge.example.test/TEST/app.git","main","b".repeat(40),
                "spire/TEST-build","TEST task","codex","TEST-model","TEST-image",List.of("TEST-original/**"),60,scm(id,"TEST-original-secret"),harness),binding);
    }
    RunCommand.PublishWorkRun permit(String id,WorkRunBinding work) throws Exception {
        return new RunCommand.PublishWorkRun(id,new WorkPublicationPermit(work,UUID.randomUUID(),"c".repeat(40),Instant.parse("2026-09-14T01:00:00Z"),
                Instant.parse("2026-09-14T01:00:30Z"),List.of("TEST-current/**")),scm(id,"TEST-current-secret"));
    }
    @Test void heldTopologyUsesTheDistinctPublisher(){var unit=assertDoesNotThrow(()->builder.buildHeld(command("TEST-run",null),new CodexAdapter()));assertEquals(List.of("spire-publish-held"),unit.publisher().argv());}
    @Test void publicationRequiresTheRetainedUnitIdentity() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(command("TEST-other",null),new CodexAdapter());var permit=permit(original.runId(),binding);
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,permit));
    }
    @Test void publicationRequiresTheOriginalCommandIdentity() throws Exception {
        var original=command("TEST-other",null);var held=builder.buildHeld(command("TEST-run",null),new CodexAdapter());var permit=permit("TEST-run",binding);
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,permit));
    }
    @Test void publicationRequiresTheOriginalWorkBinding() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());
        var permit=permit(original.runId(),new WorkRunBinding("TEST-other-item",1,binding.buildAttemptId(),binding.preparationBinding()));
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,permit));
    }
    @Test void publicationDecryptsOnlyTheFreshScmCredential() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());var request=permit(original.runId(),binding);
        var noReusableHarness=command("TEST-run","TEST-invalid-old-harness-ciphertext");
        var resumed=assertDoesNotThrow(()->builder.publication(held,noReusableHarness,request));
        assertEquals("TEST-current-secret",resumed.publisher().environment().get("SPIRE_GIT_SECRET"));
        assertFalse(resumed.publisher().environment().containsValue("TEST-original-secret"));
        assertFalse(resumed.publisher().environment().containsValue("TEST-invalid-old-harness-ciphertext"));
    }
    @Test void publicationPreservesBothProtectedPathFloors() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());var resumed=builder.publication(held,original,permit(original.runId(),binding));
        assertEquals(Set.of("TEST-original/**","TEST-current/**"),Set.of(resumed.publisher().environment().get("SPIRE_PROTECTED_PATHS").split(",")));
    }
    @Test void theTrustedPublisherReceivesTheExactHeadAndLeaseWindow() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());var request=permit(original.runId(),binding);var resumed=builder.publication(held,original,request);
        assertEquals(List.of("spire-publish-permitted"),resumed.publisher().argv());
        assertEquals(request.permit().head(),resumed.publisher().environment().get("SPIRE_PERMITTED_HEAD"));
        assertEquals(request.permit().issuedAt().toString(),resumed.publisher().environment().get("SPIRE_PERMIT_ISSUED_AT"));
        assertEquals(request.permit().expiresAt().toString(),resumed.publisher().environment().get("SPIRE_PERMIT_EXPIRES_AT"));
    }
    @Test void publicationKeepsTheOriginalImageTopologyAndBudgets() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());builder.publisherImage="TEST-new-deployment-image";
        var resumed=builder.publication(held,original,permit(original.runId(),binding));
        assertEquals(held.publisher().image(),resumed.publisher().image());assertEquals(held.publisher().mounts(),resumed.publisher().mounts());
        assertEquals(held.init(),resumed.init());assertEquals(held.agent(),resumed.agent());assertEquals(held.enterprise(),resumed.enterprise());
        assertEquals(held.memoryBytes(),resumed.memoryBytes());assertEquals(held.nanoCpus(),resumed.nanoCpus());assertEquals(held.diskBytes(),resumed.diskBytes());assertEquals(held.wallClock(),resumed.wallClock());
    }
}
