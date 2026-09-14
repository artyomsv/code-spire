package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.WorkSourceDelivery;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worksource.WorkSourceSignal;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkIngressScopeTest extends WorkFixture {
    WorkSourceDelivery replace(UUID repository,String provider,String origin,RepoRef repo,String scope) throws Exception {
        WorkSourceDelivery original=signed("900123");
        return new WorkSourceDelivery(repository,original.sourceId(),original.registrationId(),original.registrationRevision(),provider,origin,
                repo,original.deliveryId(),new WorkSourceSignal(scope,original.signal().issue(),original.signal().hint()));
    }
    void refused(WorkSourceDelivery delivery) throws Exception {
        assertNull(intake.accept(delivery));
        assertEquals(0,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",itemId));
    }
    @Test void repositoryBindingMustMatch() throws Exception {var current=sources.get(source).orElseThrow();refused(replace(UUID.randomUUID(),"github",forge.baseUrl(),current.repository(),scope));}
    @Test void providerTypeMustMatch() throws Exception {var current=sources.get(source).orElseThrow();refused(replace(repository,"gitlab",forge.baseUrl(),current.repository(),scope));}
    @Test void forgeOriginMustMatch() throws Exception {var current=sources.get(source).orElseThrow();refused(replace(repository,"github","https://TEST-other.invalid",current.repository(),scope));}
    @Test void repositoryCoordinatesMustMatch() throws Exception {refused(replace(repository,"github",forge.baseUrl(),new RepoRef("TEST-other","TEST-repo"),scope));}
    @Test void sourceScopeMustMatch() throws Exception {var current=sources.get(source).orElseThrow();refused(replace(repository,"github",forge.baseUrl(),current.repository(),"TEST-other/TEST-repo"));}
}
