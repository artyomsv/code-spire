package dev.codespire.orchestrator.provider;

import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ResolvedActor;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ActorDisplayTest {
    @Test void renamedHandleKeepsTheStoredId() {
        UUID account=UUID.randomUUID();
        ActorResolutionResource resource=new ActorResolutionResource();
        ActorDisplay original=new ActorDisplay("900123","TEST-old","TEST Old",Instant.parse("2026-09-01T00:00:00Z"),true,"ALLOW",2);
        class Store extends ActorPolicyRegistry {
            ResolvedActor refreshed;
            @Override public Policy account(UUID id) { assertEquals(account,id); return new Policy(2,List.of(original)); }
            @Override public void refreshAccount(UUID id,ResolvedActor actor) { assertEquals(account,id);refreshed=actor; }
            @Override public void failedAccountRefresh(UUID id,String actor) { assertEquals(account,id);assertEquals("900123",actor); }
        }
        Store store=new Store();resource.policies=store;
        resource.providers=new ProviderRegistry() {
            @Override public Optional<ScmProvider> resolveById(UUID id) { assertEquals(account,id);return Optional.of(
                    new ScmProvider(account,"TEST-account","github","https://TEST-forge.example.test","bearer",null,"TEST-secret","TEST-bot",true,List.of(),"TEST-bot",null,ProviderRole.REVIEWER)); }
        };
        resource.clients=new ProviderClients() {
            @Override public ActorDirectory actorDirectory(ScmProvider provider) {return new ActorDirectory() {
                public Result lookup(String handle,String scope) {fail("Refreshing display must never resolve the old handle, which may belong to someone else");return null;}
                public Result byId(String id) {assertEquals("900123",id);return Result.found(new ResolvedActor("900123","TEST-renamed","TEST Renamed"));}
            };}
        };
        ActorDisplay after=resource.list(account,true).actors().getFirst();
        assertEquals("900123",after.providerUserId());assertEquals("TEST-renamed",after.handle());assertFalse(after.stale());
        assertEquals("900123",store.refreshed.providerUserId());assertEquals("TEST-renamed",store.refreshed.handle());
        resource.clients=new ProviderClients() {
            @Override public ActorDirectory actorDirectory(ScmProvider provider) {return new ActorDirectory() {
                public Result lookup(String handle,String scope){throw new AssertionError("Never lookup a display handle");}
                public Result byId(String id){return Result.failed(Status.UNAVAILABLE,"TEST-outage");}
            };}
        };
        ActorDisplay stale=resource.list(account,true).actors().getFirst();
        assertEquals("900123",stale.providerUserId());assertEquals("TEST-old",stale.handle());assertTrue(stale.stale());
        store.refreshed=null;
        resource.clients=new ProviderClients() {
            @Override public ActorDirectory actorDirectory(ScmProvider provider) {return new ActorDirectory() {
                public Result lookup(String handle,String scope){throw new AssertionError("Never lookup a display handle");}
                public Result byId(String id){return Result.found(new ResolvedActor("900456","TEST-impostor","TEST Other"));}
            };}
        };
        ActorDisplay mismatch=resource.list(account,true).actors().getFirst();
        assertEquals("900123",mismatch.providerUserId());assertEquals("TEST-old",mismatch.handle());assertTrue(mismatch.stale());
        assertNull(store.refreshed,"A different identity must not replace the stored person's display");
    }
}
