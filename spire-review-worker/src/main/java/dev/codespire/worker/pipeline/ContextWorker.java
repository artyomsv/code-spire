package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.code.CodeContextProvider;
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
import dev.codespire.worker.adapters.WorkerContextReferences;
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
    /** {@link ContextItem#kind()} for a code-context provider's contributions — see {@link #corpusOf}. */
    private static final String CODE_SNIPPET_KIND = "CODE_SNIPPET";

    @Inject
    WorkerContextClients contextClients;

    /** Extraction and dedup of references — the only place that knows any source's syntax. */
    @Inject
    WorkerContextReferences contextReferences;

    @Inject
    PostgresBlobStore blobStore;

    @Inject
    ResultsEmitter results;

    @Inject
    ObjectMapper mapper;

    public void gatherContext(GatherContext command) {
        List<ContextProvider> providers = contextClients.forCommand(command);

        Set<String> level1 = command.references() == null ? Set.of() : command.references();

        // expectedSources is computed from the PR's own references (level 1) — informational for the timeline.
        ContextRequest probe = request(command, level1, Set.of());
        Set<String> expected = providers.stream().filter(p -> p.supports(probe))
                .map(ContextProvider::source)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        results.emit(new ContextRequested(request(command, level1, expected)));

        // Redelivery guard: a re-delivered GatherContext must not accumulate blobs.
        blobStore.deleteByReview(command.reviewId());

        List<ContextContribution> contributions = collect(command, providers, level1);

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
     * text retrieved so far for NEW references and resolves those, deduped
     * against everything already requested. Capped at {@link #MAX_DEPTH} to break reference cycles.
     */
    private List<ContextContribution> collect(GatherContext command, List<ContextProvider> providers,
                                              Set<String> level1) {
        List<ContextContribution> all = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(contextReferences.normalizeAll(level1));
        Set<String> next = level1;

        for (int level = 1; level <= MAX_DEPTH; level++) {
            if (next.isEmpty()) {
                break;
            }
            ContextRequest request = request(command, next, Set.of());
            List<ContextProvider> supported = providers.stream().filter(p -> p.supports(request)).toList();
            List<ContextContribution> round = fanOut(supported, request);
            all.addAll(round);

            if (level == MAX_DEPTH) {
                break; // don't discover a further level we won't fetch
            }
            // Everything RETRIEVED is seen, not just everything requested. An item carries its own
            // address, and the corpus below includes it: without this, a source whose grammar
            // recognises its own item urls (GitHub's and GitLab's do; Jira and Confluence normalize a
            // url down to the key or page id already seen) reads that address as a fresh reference and
            // re-fetches the very item it came from — spending the next level's bounded reference
            // budget on items already in hand.
            seen.addAll(contextReferences.normalizeAll(urisOf(round)));
            // Discover the next level from the text retrieved this round — each extractor mines its
            // own shapes and dedupes in its own normalized form, so nothing here parses anything.
            next = contextReferences.freshReferencesIn(seen, corpusOf(round));
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
                .map(p -> contributionFuture(p, request))
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

    /**
     * {@link ContextProvider#contribute} for every provider except {@link CodeContextProvider}, whose
     * per-invocation {@link CodeContextProvider.Counts} — extracted / resolved / contributed /
     * droppedForBudget — cannot be read off the SPI's plain {@link ContextContribution} return type, so
     * the worker calls {@link CodeContextProvider#resolve} directly and logs them (see
     * {@link #logCodeCounts}) before handing back the same {@link ContextContribution} {@code contribute}
     * would have returned.
     */
    private CompletableFuture<ContextContribution> contributionFuture(ContextProvider provider,
            ContextRequest request) {
        if (provider instanceof CodeContextProvider codeProvider) {
            return CompletableFuture.supplyAsync(() -> codeProvider.resolve(request))
                    .thenApply(resolved -> {
                        logCodeCounts(resolved.counts());
                        return resolved.contribution();
                    });
        }
        return provider.contribute(request).toCompletableFuture();
    }

    /**
     * Counts carry no source text — safe to log, unlike a {@code CODE_SNIPPET} item's title, body, or
     * path, which quote retrieved source and must never appear in a log line.
     */
    private void logCodeCounts(CodeContextProvider.Counts counts) {
        LOG.infof("Code context resolution: extracted=%d resolved=%d contributed=%d droppedForBudget=%d",
                counts.extracted(), counts.resolved(), counts.contributed(), counts.droppedForBudget());
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

    private static ContextRequest request(GatherContext command, Set<String> references,
                                          Set<String> expected) {
        return new ContextRequest(command.reviewId(), command.repo(), command.prId(), command.commit(),
                references, expected, command.scmType(), command.repoRules(), command.codeReferences());
    }

    /** The address of every item retrieved this round — already in hand, so never a fresh reference. */
    private static Set<String> urisOf(List<ContextContribution> contributions) {
        Set<String> uris = new LinkedHashSet<>();
        for (ContextContribution c : contributions) {
            if (c.status() == ContribStatus.OK && c.items() != null) {
                for (ContextItem item : c.items()) {
                    if (item.uri() != null && !item.uri().isBlank()) {
                        uris.add(item.uri());
                    }
                }
            }
        }
        return uris;
    }

    /**
     * All retrieved text this round (title + body + uri of OK items) — the corpus the next level
     * mines. {@code CODE_SNIPPET} items are excluded: their body IS source code, so a ticket-shaped
     * string sitting inside a code comment (e.g. {@code // see PROJ-123 for background}) would
     * otherwise be mined as a genuine reference and fetched — turning a source comment into a live
     * context fetch against a system nobody mentioned in the PR. This line reads like an
     * optimization (skip the bulkiest items); it is actually the fix for that hazard.
     */
    private static String corpusOf(List<ContextContribution> contributions) {
        StringBuilder sb = new StringBuilder();
        for (ContextContribution c : contributions) {
            if (c.status() == ContribStatus.OK && c.items() != null) {
                for (ContextItem item : c.items()) {
                    if (CODE_SNIPPET_KIND.equals(item.kind())) {
                        continue;
                    }
                    sb.append(item.title()).append('\n').append(item.body()).append('\n');
                    if (item.uri() != null) {
                        sb.append(item.uri()).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    // Dedup across rounds moved to WorkerContextReferences: comparing a reference needs to know its
    // shape (a case-insensitive key, a link that identifies a page), which is the extractor's to know.

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
