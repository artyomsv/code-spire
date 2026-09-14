package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.RepositoryPermissionSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ScmProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.codespire.contract.scm.RepositoryPermission.State.*;

class FixPermissionBudgetTest {
    FixPermissionService service(RepositoryPermissionSource source) {
        FixPermissionService service=new FixPermissionService();
        service.clients=new ProviderClients() {
            @Override public RepositoryPermissionSource repositoryPermissionSource(ScmProvider account) { return source; }
        };
        return service;
    }
    @Test void totalBudgetCancelsTheWholeLookup() throws Exception {
        CountDownLatch interrupted=new CountDownLatch(1);
        FixPermissionService service=service((repo,id)-> {
            try { Thread.sleep(21000); } catch (InterruptedException failure) { interrupted.countDown();Thread.currentThread().interrupt(); }
            return new RepositoryPermission(CAN_PUSH,"TEST-eventual-write");
        });
        assertEquals(UNKNOWN,service.measure(null,new RepoRef("TEST-owner","TEST-repo"),"900123").state());
        assertTrue(interrupted.await(1,TimeUnit.SECONDS),"a timed-out read must be cancelled, including remaining pages");
    }
    @Test void failedLookupCannotGrant() {
        FixPermissionService service=service((repo,id)-> { throw new IllegalStateException("TEST-secret"); });
        RepositoryPermission result=service.measure(null,new RepoRef("TEST-owner","TEST-repo"),"900123");
        assertEquals(UNKNOWN,result.state());assertFalse(result.detail().contains("TEST-secret"));
    }
    @Test void interruptionRefusesAndPreservesTheSignal() {
        FixPermissionService service=service((repo,id)-> { try { Thread.sleep(5000); } catch(InterruptedException failure){Thread.currentThread().interrupt();} return new RepositoryPermission(CAN_PUSH,"TEST-write"); });
        Thread.currentThread().interrupt();
        try {
            assertEquals(UNKNOWN,service.measure(null,new RepoRef("TEST-owner","TEST-repo"),"900123").state());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
