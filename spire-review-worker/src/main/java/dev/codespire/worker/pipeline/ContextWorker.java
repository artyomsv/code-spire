package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ContextContributed;
import dev.codespire.contract.event.IntegrationEvent.ContextRequested;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.worker.adapters.PostgresBlobStore;
import dev.codespire.worker.adapters.WorkerContextClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The context aggregator (CONTRACT §8): resolves the per-command
 * {@link ContextProvider}s, fans out to those that support the request, and
 * collects their contributions under a bounded wall-clock timeout. Whatever
 * arrives is serialized into an {@link AssembledContext} and persisted encrypted
 * to the {@link BlobStore}; its ref is threaded onto {@code GenerateReview}.
 *
 * <p>Worker-local by design: a single worker has no cross-process arrivals to
 * reconcile, so the completeness/timeout policy is an in-process
 * {@code allOf(...).get(timeout)} rather than an event-sourced saga + timer. The
 * ContextRequested / ContextContributed / ContextAssembled events still flow for
 * the dashboard timeline.
 */
@ApplicationScoped
public class ContextWorker {

    private static final Logger LOG = Logger.getLogger(ContextWorker.class);
    /** Completeness/timeout budget for the whole fan-out (CONTRACT §8, default 20s). */
    private static final long TIMEOUT_SECONDS = 20;

    @Inject
    WorkerContextClients contextClients;

    @Inject
    PostgresBlobStore blobStore;

    @Inject
    ResultsEmitter results;

    @Inject
    ObjectMapper mapper;

    public void gatherContext(GatherContext command) {
        List<ContextProvider> providers = contextClients.forCommand(command);
        ContextRequest probe = request(command, Set.of());
        List<ContextProvider> supported = providers.stream().filter(p -> p.supports(probe)).toList();
        Set<String> expected = supported.stream()
                .map(ContextProvider::source)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        ContextRequest request = request(command, expected);
        results.emit(new ContextRequested(request));

        // Redelivery guard: a re-delivered GatherContext must not accumulate blobs.
        blobStore.deleteByReview(command.reviewId());

        List<ContextContribution> contributions = fanOut(supported, request);

        List<ContextItem> items = new ArrayList<>();
        Set<String> contributing = new LinkedHashSet<>();
        for (ContextContribution c : contributions) {
            results.emit(new ContextContributed(command.reviewId(), c));
            if (c.status() == ContribStatus.OK && c.items() != null) {
                items.addAll(c.items());
                contributing.add(c.source());
            }
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(contributing);

        String contextRef = items.isEmpty() ? null : persist(command.reviewId(), items, contributing, missing);
        results.emit(new ContextAssembled(command.reviewId(), command.prId(), command.commit(),
                contextRef, contributing, missing));
    }

    /**
     * Runs every supported provider concurrently and waits up to the timeout. A
     * provider that has not finished (slow API) or completed exceptionally is
     * recorded as an ERROR contribution so the miss is visible on the timeline
     * without aborting the review.
     */
    private List<ContextContribution> fanOut(List<ContextProvider> supported, ContextRequest request) {
        List<CompletableFuture<ContextContribution>> futures = supported.stream()
                .map(p -> p.contribute(request).toCompletableFuture())
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warnf("Context fan-out timed out after %ds for %d provider(s)", TIMEOUT_SECONDS, supported.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Individual failures are inspected per-future below; nothing to do here.
            LOG.debugf(e, "A context provider completed exceptionally");
        }

        List<ContextContribution> contributions = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<ContextContribution> f = futures.get(i);
            String source = supported.get(i).source();
            if (f.isDone() && !f.isCompletedExceptionally()) {
                contributions.add(f.getNow(null));
            } else {
                f.cancel(true);
                LOG.warnf("Context provider %s did not contribute within the budget", source);
                contributions.add(new ContextContribution(source, ContribStatus.ERROR, List.of(), 0));
            }
        }
        return contributions;
    }

    private String persist(String reviewId, List<ContextItem> items,
                           Set<String> contributing, Set<String> missing) {
        AssembledContext assembled = new AssembledContext(null, items, contributing, missing);
        try {
            BlobStore.BlobRef ref = blobStore.put(BlobStore.Kind.CONTEXT, reviewId,
                    mapper.writeValueAsBytes(assembled));
            return ref.key();
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize assembled context for " + reviewId, e);
        }
    }

    private static ContextRequest request(GatherContext command, Set<String> expected) {
        return new ContextRequest(command.reviewId(), command.repo(), command.prId(), command.commit(),
                command.ticketKeys(), command.links(), expected);
    }
}
