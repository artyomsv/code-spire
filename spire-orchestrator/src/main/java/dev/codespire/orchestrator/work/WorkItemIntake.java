package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.WorkSourceDelivery;
import dev.codespire.worksource.*;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** Signed delivery and scanner use the identical evidence and policy path. No review saga dependency. */
@ApplicationScoped
public class WorkItemIntake {
    @Inject ObjectMapper mapper;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemStore store;
    @Inject WorkGateChannels gates;

    @Incoming("work-integration-in") @Blocking
    public void on(String payload) throws JsonProcessingException { accept(mapper.readValue(payload, WorkSourceDelivery.class)); }

    public String accept(WorkSourceDelivery delivery) {
        if (delivery.sourceId() == null || delivery.repositoryId() == null || delivery.signal() == null) return null;
        WorkSourceRegistry.Source source = sources.get(delivery.sourceId()).orElse(null);
        if (source == null || !source.enabled() || !source.repositoryId().equals(delivery.repositoryId())
                || !source.scm().providerType().equals(delivery.providerType()) || !source.forgeOrigin().equals(delivery.forgeOrigin())
                || !source.repository().equals(delivery.repo()) || !source.scope().equals(delivery.signal().externalScope())) return null;
        if(delivery.signal().activity()!=null) {
            var issue=delivery.signal().issue();
            if(!source.origin().equals(issue.ref().origin()) || source.type()!=issue.ref().type()
                    || !source.projectId().equals(issue.ref().projectId()))return null;
            return gates.tracker(source,delivery);
        }
        return reconcile(source, delivery.signal().issue(), delivery.signal().hint(), delivery.deliveryId());
    }

    public String reconcile(WorkSourceRegistry.Source source, WorkIssueLocation issue, LabelEvent hint, String deliveryId) {
        if (!source.enabled() || !source.origin().equals(issue.ref().origin()) || source.type() != issue.ref().type()
                || !source.projectId().equals(issue.ref().projectId())) return null;
        WorkPolicyRegistry.Policy policy = policies.get(source.repositoryId());
        WorkEvidence evidence = WorkEvidence.collect(() -> sources.client(source), issue, hint);
        return store.reconcile(source, policy.revision(), evidence, deliveryId);
    }
}
