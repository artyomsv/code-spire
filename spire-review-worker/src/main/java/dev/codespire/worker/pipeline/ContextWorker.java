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
import dev.codespire.context.confluence.ConfluenceLinks;
import dev.codespire.context.jira.JiraTicketKeys;
import dev.codespire.worker.adapters.PostgresBlobStore;
import dev.codespire.worker.adapters.WorkerContextClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p><b>Bounded two-level collection.</b> Level 1 fetches the references carried on
 * the command (the Jira keys and links parsed from the PR itself). The retrieved
 * text often points further — a Jira ticket that links a Confluence page, or another
 * ticket — so level 2 mines the level-1 item bodies for NEW references and fetches
 * those once. Collection stops there ({@link #MAX_DEPTH}): this is what breaks a
 * jira→confluence→jira→… cycle. A reference already fetched at level 1 (e.g. a
 * Confluence page linked from both the PR and a ticket) is de-duplicated, not re-fetched.
 */
@ApplicationScoped
public class ContextWorker {

    private static final Logger LOG = Logger.getLogger(ContextWorker.class);
    /** Completeness/timeout budget for one fan-out level (CONTRACT §8, default 20s). */
    private static final long TIMEOUT_SECONDS = 20;
    /** Collection depth cap: level 1 (the PR's refs) + one hop (refs found inside level-1 content). */
    private static final int MAX_DEPTH = 2;

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

        Set<String> l1Keys = command.ticketKeys() == null ? Set.of() : command.ticketKeys();
        List<String> l1Links = command.links() == null ? List.of() : command.links();

        // expectedSources is computed from the PR's own references (level 1) — informational for the timeline.
        ContextRequest probe = request(command, l1Keys, l1Links, Set.of());
        Set<String> expected = providers.stream().filter(p -> p.supports(probe))
                .map(ContextProvider::source)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        results.emit(new ContextRequested(request(command, l1Keys, l1Links, expected)));

        // Redelivery guard: a re-delivered GatherContext must not accumulate blobs.
        blobStore.deleteByReview(command.reviewId());

        List<ContextContribution> contributions = collect(command, providers, l1Keys, l1Links);

        // A provider may contribute at both levels; merge per source into one Contributed event.
        List<ContextItem> items = new ArrayList<>();
        Set<String> seenUris = new LinkedHashSet<>();
        Set<String> contributing = new LinkedHashSet<>();
        for (ContextContribution c : mergeBySource(contributions)) {
            results.emit(new ContextContributed(command.reviewId(), c));
            if (c.status() == ContribStatus.OK && c.items() != null) {
                for (ContextItem item : c.items()) {
                    if (item.uri() == null || seenUris.add(item.uri())) {
                        items.add(item); // de-dup the same page/ticket referenced from two places
                    }
                }
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
     * The bounded two-level fetch. Level 1 resolves the PR's own references; each subsequent level mines the
     * text retrieved so far for NEW references (Jira keys, Confluence links) and resolves those, deduped
     * against everything already requested. Capped at {@link #MAX_DEPTH} to break reference cycles.
     */
    private List<ContextContribution> collect(GatherContext command, List<ContextProvider> providers,
                                              Set<String> keys, List<String> links) {
        List<ContextContribution> all = new ArrayList<>();
        Set<String> seenKeys = normalize(keys, ContextWorker::normKey);
        Set<String> seenLinks = normalize(links, ContextWorker::normLink);
        Set<String> nextKeys = keys;
        List<String> nextLinks = links;

        for (int level = 1; level <= MAX_DEPTH; level++) {
            if (nextKeys.isEmpty() && nextLinks.isEmpty()) {
                break;
            }
            ContextRequest request = request(command, nextKeys, nextLinks, Set.of());
            List<ContextProvider> supported = providers.stream().filter(p -> p.supports(request)).toList();
            List<ContextContribution> round = fanOut(supported, request);
            all.addAll(round);

            if (level == MAX_DEPTH) {
                break; // don't discover a further level we won't fetch
            }
            // Discover the next level's references from the text retrieved this round.
            String corpus = corpusOf(round);
            nextKeys = fresh(JiraTicketKeys.candidates(corpus), seenKeys, ContextWorker::normKey);
            nextLinks = List.copyOf(fresh(ConfluenceLinks.candidates(corpus), seenLinks, ContextWorker::normLink));
        }
        return all;
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

    private static ContextRequest request(GatherContext command, Set<String> keys, List<String> links,
                                          Set<String> expected) {
        return new ContextRequest(command.reviewId(), command.repo(), command.prId(), command.commit(),
                keys, links, expected);
    }

    /** All retrieved text this round (title + body + uri of OK items) — the corpus the next level mines. */
    private static String corpusOf(List<ContextContribution> contributions) {
        StringBuilder sb = new StringBuilder();
        for (ContextContribution c : contributions) {
            if (c.status() == ContribStatus.OK && c.items() != null) {
                for (ContextItem item : c.items()) {
                    sb.append(item.title()).append('\n').append(item.body()).append('\n');
                    if (item.uri() != null) {
                        sb.append(item.uri()).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Candidates whose normalized form has not been requested before; adds the fresh ones to {@code seen}. */
    private static Set<String> fresh(Collection<String> candidates, Set<String> seen,
                                     java.util.function.Function<String, String> norm) {
        Set<String> out = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (seen.add(norm.apply(candidate))) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static Set<String> normalize(Collection<String> values, java.util.function.Function<String, String> norm) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            out.add(norm.apply(v));
        }
        return out;
    }

    /** Jira keys compare case-insensitively. */
    private static String normKey(String key) {
        return key == null ? "" : key.toUpperCase();
    }

    /** Confluence links compare by page id when they carry one, so two URL shapes for one page dedupe. */
    private static String normLink(String link) {
        return ConfluenceLinks.pageId(link).orElse(link == null ? "" : link.trim());
    }

    /**
     * Combine a source's contributions across levels into one: items concatenated, latency summed, status OK
     * when anything resolved, else ERROR if any level errored, else EMPTY. Preserves first-seen source order.
     */
    private static List<ContextContribution> mergeBySource(List<ContextContribution> contributions) {
        Map<String, List<ContextContribution>> bySource = new LinkedHashMap<>();
        for (ContextContribution c : contributions) {
            bySource.computeIfAbsent(c.source(), k -> new ArrayList<>()).add(c);
        }
        List<ContextContribution> merged = new ArrayList<>();
        for (Map.Entry<String, List<ContextContribution>> entry : bySource.entrySet()) {
            List<ContextItem> items = new ArrayList<>();
            long latency = 0;
            boolean anyError = false;
            for (ContextContribution c : entry.getValue()) {
                if (c.items() != null) {
                    items.addAll(c.items());
                }
                latency += c.latencyMs();
                anyError |= c.status() == ContribStatus.ERROR;
            }
            ContribStatus status = !items.isEmpty() ? ContribStatus.OK
                    : anyError ? ContribStatus.ERROR : ContribStatus.EMPTY;
            merged.add(new ContextContribution(entry.getKey(), status, items, latency));
        }
        return merged;
    }
}
