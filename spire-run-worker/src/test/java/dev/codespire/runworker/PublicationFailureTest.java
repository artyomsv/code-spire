package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class PublicationFailureTest {
    @Test void bothOldAndRotatedCredentialsAreRedactedFromPublisherFailures() throws Exception {
        var fixture=new HeldRunUnitBuilderTest();var original=fixture.command("TEST-redaction",null);
        var publication=fixture.permit(original.runId(),fixture.binding);
        var failures=new RunFailures();failures.credentials=fixture.builder.credentials;failures.enterprise=fixture.builder.enterprise;
        String old="TEST-original-secret",fresh="TEST-current-secret";
        String oldBasic=Base64.getEncoder().encodeToString(("TEST-bot:"+old).getBytes(StandardCharsets.UTF_8));
        String newBasic=Base64.getEncoder().encodeToString(("TEST-bot:"+fresh).getBytes(StandardCharsets.UTF_8));
        var result=failures.ofPublication(original.execution(),publication,"PUBLISHER_FAILED","TEST failure: "+old+" "+fresh+" "+oldBasic+" "+newBasic);
        assertEquals("PUBLISHER_FAILED",result.cause());assertTrue(result.detail().contains("TEST failure:"));
        for(String secret:new String[]{old,fresh,oldBasic,newBasic})assertFalse(result.detail().contains(secret),"Every original and rotated wire form must be redacted");
    }
    @Test void anUnreadableRotatedCredentialCannotProduceAFailurePayload() throws Exception {
        var fixture=new HeldRunUnitBuilderTest();var original=fixture.command("TEST-redaction",null);
        var publication=fixture.permit(original.runId(),fixture.binding);
        var failures=new RunFailures();failures.credentials=fixture.builder.credentials;failures.enterprise=fixture.builder.enterprise;
        var unreadable=new RunCommand.PublishWorkRun(publication.runId(),publication.permit(),"TEST-invalid-current-ciphertext");
        assertThrows(RuntimeException.class,()->failures.ofPublication(original.execution(),unreadable,"PUBLISHER_FAILED","TEST credential-bearing error"));
    }
}
