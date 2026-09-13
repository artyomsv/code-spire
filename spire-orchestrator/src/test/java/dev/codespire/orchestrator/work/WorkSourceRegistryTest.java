package dev.codespire.orchestrator.work;

import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.worksource.WorkSourceType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkSourceRegistryTest extends WorkFixture {
    @Test void sourceHasAnExplicitAccountAndStableScope(){var saved=sources.get(source).orElseThrow();assertEquals(account,saved.accountId());assertEquals("10001",saved.projectId());assertEquals(repository,saved.repositoryId());assertEquals(Set.of("900123"),saved.allowedActors());}
    @Test void duplicateSourceAndTargetIsRefused(){assertThrows(RuntimeException.class,()->administration.create(new WorkSourceAdministration.Input("TEST-duplicate",WorkSourceType.GITHUB,forge.baseUrl(),scope,repository,account,true)));assertEquals(1,sources.list().stream().filter(row->row.repositoryId().equals(repository)).count());}
    @Test void sourceCannotBorrowAnotherRepositoryScope(){
        forge.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/repos/TEST-other/TEST-repo"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"id\":20001,\"full_name\":\"TEST-other/TEST-repo\",\"html_url\":\""+forge.baseUrl()+"/TEST-other/TEST-repo\"}")));
        assertThrows(IllegalArgumentException.class,()->administration.create(new WorkSourceAdministration.Input("TEST-foreign",WorkSourceType.GITHUB,forge.baseUrl(),"TEST-other/TEST-repo",repository,account,true)));
    }
    @Test void sourceCannotBorrowAnotherOrigin(){assertThrows(IllegalArgumentException.class,()->administration.create(new WorkSourceAdministration.Input("TEST-foreign",WorkSourceType.GITHUB,"https://TEST-other.invalid",scope,repository,account,true)));}
    @Test void disabledAccountStopsSourceResolution() throws Exception {
        providers.update(account,new ProviderInput("TEST-disabled","github",forge.baseUrl(),"bearer",null,null,"TEST-bot",false,List.of(),"TEST-bot",null,"FACTORY"));
        assertFalse(sources.get(source).orElseThrow().enabled());assertNull(intake.accept(signed("900123")));
        assertEquals(0,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
    }
    @Test void disabledRepositoryStopsSourceResolution() throws Exception {
        var repo=repositories.get(repository).orElseThrow();
        repositories.update(repository,repo.revision(),new RepositoryInput("github",forge.baseUrl(),repo.workspace(),repo.slug(),false,null,account));
        assertFalse(sources.get(source).orElseThrow().enabled());assertNull(intake.accept(signed("900123")));
    }
    @Test void removingAnAllowedActorNarrowlyChangesSourceAuthority() throws Exception {
        var before=sources.get(source).orElseThrow();var after=administration.removeActor(source,"900123",before.version().source());
        assertEquals(before.version().source()+1,after.version().source());assertTrue(after.allowedActors().isEmpty());
        assertEquals(itemId,intake.accept(signed("900123")));assertNull(store.load(itemId).policy().selected());
        assertEquals("actor_not_allowed",store.load(itemId).policy().ignored().getFirst().reason());
    }
    @Test void staleActorPolicySaveIsRefused(){assertThrows(IllegalArgumentException.class,()->administration.saveActor(source,new WorkSourceAdministration.ActorInput("TEST-person","900123",1)));assertEquals(2,sources.get(source).orElseThrow().version().source());}
    @Test void aSubmittedIdMustMatchTheResolvedPerson(){
        forge.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/user/900999"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"id\":900999,\"login\":\"TEST-other-person\"}")));
        assertThrows(IllegalArgumentException.class,()->administration.saveActor(source,new WorkSourceAdministration.ActorInput("TEST-person","900999",2)));
        assertEquals(Set.of("900123"),sources.get(source).orElseThrow().allowedActors());
    }
    @Test void usedByNamesTheWorkSource(){assertTrue(providers.get(account).orElseThrow().usedBy().contains(sources.get(source).orElseThrow().name()));}
    @Test void anAccountReferencedByAWorkSourceCannotBeDeleted(){assertThrows(dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict.class,()->providers.delete(account));assertTrue(providers.get(account).isPresent());}
}
