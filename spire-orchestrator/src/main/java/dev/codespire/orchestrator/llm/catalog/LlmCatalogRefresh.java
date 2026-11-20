package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.llmcatalog.CatalogDocument;
import dev.codespire.llmcatalog.CatalogException;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Reads the catalog from its URL at startup and every refresh interval, and on "Reload now" (spec §6.3).
 * A valid new file is stored, announced to every instance and swapped in; anything else keeps the catalog
 * in use and records why. With the URL {@code none} nothing is read and nothing is written (spec §6.5).
 */
@ApplicationScoped
public class LlmCatalogRefresh {

    static final String URL_NOT_HTTPS = "url_not_https";
    private static final String HTTPS = "https";
    private static final Logger LOG = Logger.getLogger(LlmCatalogRefresh.class);

    public enum RefreshOutcome { DISABLED, NOT_MODIFIED, UNCHANGED, REPLACED, INVALID, FAILED }

    @Inject
    LlmModelCatalog catalog;

    @Inject
    LlmCatalogStateRepository states;

    /** Built on first use: an HttpClient starts a thread, which a CDI proxy or a unit test need not own. */
    CatalogFetcher fetcher;

    /** Where the first refresh runs: off the startup thread, so a slow URL never delays boot. */
    Executor startup = task -> Thread.ofVirtual().name("llm-catalog-first-refresh").start(task);

    /**
     * Once at startup, whatever the interval: {@code refresh-interval: off} still reads the URL once
     * (spec §6.1). The schedule below covers every later read.
     */
    void onStart(@Observes StartupEvent started) {
        if (catalog.remoteLoadingOff()) {
            LOG.info("LLM catalog: remote loading is off (url none); using the copy built into this image");
            return;
        }
        if (!isHttps(catalog.url())) {
            LOG.errorf("LLM catalog: spire.llm.catalog.url must be https; it is never read (%s)", URL_NOT_HTTPS);
        }
        startup.execute(this::refreshQuietly);
    }

    // Delayed: the startup read above already covers boot.
    @Scheduled(every = "${spire.llm.catalog.refresh-interval}", delayed = "1m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduled() {
        refreshQuietly();
    }

    /** A scheduled or startup read: a fault is logged, and the next interval tries again. */
    void refreshQuietly() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            LOG.warnf("LLM catalog refresh failed (%s); the catalog in use is kept", e.getClass().getSimpleName());
        }
    }

    public synchronized RefreshOutcome refreshNow() {
        if (catalog.remoteLoadingOff()) {
            return RefreshOutcome.DISABLED;
        }
        String url = catalog.url().strip();
        if (!isHttps(url)) {
            states.recordFailure(URL_NOT_HTTPS);
            return RefreshOutcome.FAILED;
        }
        LlmCatalogState state = states.read();
        if (state.document() == null) {
            state = storeBundledCopy();
        }
        String etag = url.equals(state.url()) ? state.etag() : null;
        return switch (fetcher().fetch(URI.create(url), etag)) {
            case CatalogFetcher.NotModified ignored -> notModified(etag);
            case CatalogFetcher.Failed failed -> failed(failed.reason());
            case CatalogFetcher.Fetched fetched -> accept(fetched, state, url);
        };
    }

    private CatalogFetcher fetcher() {
        if (fetcher == null) {
            fetcher = new CatalogFetcher(CatalogFetcher.clientBuilder().build());
        }
        return fetcher;
    }

    private RefreshOutcome notModified(String etag) {
        states.recordUnchanged(etag);
        return RefreshOutcome.NOT_MODIFIED;
    }

    private RefreshOutcome failed(String reason) {
        states.recordFailure(reason);
        LOG.warnf("LLM catalog could not be read (%s); the catalog in use is kept", reason);
        return RefreshOutcome.FAILED;
    }

    private RefreshOutcome accept(CatalogFetcher.Fetched fetched, LlmCatalogState state, String url) {
        CatalogSnapshot next;
        try {
            next = LlmModelCatalog.parse(fetched.body());
        } catch (CatalogException invalid) {
            states.recordFailure("invalid: " + CatalogText.safe(invalid.getMessage()));
            LOG.warnf("LLM catalog at the configured URL is invalid (%s); the catalog in use is kept",
                    CatalogText.safe(invalid.getMessage()));
            return RefreshOutcome.INVALID;
        }
        if (next.sha256().equals(state.sha256()) && url.equals(state.url())) {
            states.recordUnchanged(fetched.etag());
            return RefreshOutcome.UNCHANGED;
        }
        CatalogSnapshot current = catalog.snapshot();
        CatalogChange change = CatalogChange.between(documentOf(current), current.sha256(), documentOf(next),
                next.sha256());
        states.storeDocument(new LlmCatalogStateRepository.StoredDocument(fetched.body(), next.sha256(),
                fetched.etag(), "remote", url, change.toJson()));
        if (!catalog.replaceIf(current.sha256(), next)) {
            LOG.info("LLM catalog: a newer file arrived while this one was stored; keeping the newer one");
        }
        LOG.info(change.summary());
        return RefreshOutcome.REPLACED;
    }

    /**
     * A new database: the bundled copy is written first, so every instance starts from the same row and a
     * remote file then replaces it (spec §6.5). Never reached with the URL {@code none}.
     */
    private LlmCatalogState storeBundledCopy() {
        String text = catalog.bundledText();
        CatalogSnapshot bundled = catalog.bundled();
        if (text == null || bundled.sha256() == null) {
            return states.read();
        }
        states.storeDocument(new LlmCatalogStateRepository.StoredDocument(text, bundled.sha256(), null, "bundled",
                null, null));
        return states.read();
    }

    private static CatalogDocument documentOf(CatalogSnapshot snapshot) {
        return new CatalogDocument(List.of(), snapshot.models());
    }

    static boolean isHttps(String url) {
        if (url == null) {
            return false;
        }
        try {
            String scheme = URI.create(url.strip()).getScheme();
            return Objects.equals(HTTPS, scheme == null ? null : scheme.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
