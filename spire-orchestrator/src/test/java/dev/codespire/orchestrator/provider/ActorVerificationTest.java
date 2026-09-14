package dev.codespire.orchestrator.provider;

import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ResolvedActor;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Isolates the service's second identity check from the adapters' independent checks. */
class ActorVerificationTest {
    private static final ResolvedActor PERSON=new ResolvedActor("900123","TEST-person","TEST Person");
    private static final ScmProvider ACCOUNT=new ScmProvider(UUID.randomUUID(),"TEST-account","github",
            "https://TEST-forge.example.test","bearer",null,"TEST-token","TEST-bot",true,List.of(),"TEST-bot",null,ProviderRole.REVIEWER);

    @Test void stableIdRecheckMustConfirmExactlyTheSelectedPerson() throws Exception {
        for (ActorDirectory.Result result:List.of(
                ActorDirectory.Result.found(new ResolvedActor("900456","TEST-person","TEST Other")),
                new ActorDirectory.Result(ActorDirectory.Status.FOUND,List.of(PERSON,PERSON),null),
                new ActorDirectory.Result(ActorDirectory.Status.SELECTION_REQUIRED,List.of(PERSON),null))) {
            WebApplicationException failure=assertThrows(WebApplicationException.class,()->verify(result));
            assertEquals(422,failure.getResponse().getStatus());
        }
        assertEquals(PERSON,verify(ActorDirectory.Result.found(PERSON)));
    }

    private ResolvedActor verify(ActorDirectory.Result recheck) throws Exception {
        ActorResolutionResource resource=new ActorResolutionResource();
        resource.clients=new ProviderClients() {
            @Override public ActorDirectory actorDirectory(ScmProvider account) {
                assertEquals(ACCOUNT,account);
                return new ActorDirectory() {
                    public Result lookup(String handle,String scope) {assertEquals("@TEST-person",handle);return Result.found(PERSON);}
                    public Result byId(String id) {assertEquals(PERSON.providerUserId(),id);return recheck;}
                };
            }
        };
        var method=ActorResolutionResource.class.getDeclaredMethod("verify",ScmProvider.class,ActorResolutionResource.Input.class,String.class);
        method.setAccessible(true);
        try {return (ResolvedActor)method.invoke(resource,ACCOUNT,new ActorResolutionResource.Input("@TEST-person","900123",null,1,"ALLOW"),null);}
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
}
