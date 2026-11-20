package dev.codespire.orchestrator.llm.catalog;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles.FakeManualModels;
import static dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles.FakeStates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The refresh flow (spec §6.3) on hand-built collaborators and a stubbed fetcher. */
class LlmCatalogRefreshTest {

    private static final String REMOTE = "https://example.invalid/catalog.yaml";

    private static String document(long output, String observedAt) {
        return """
                providers:
                  - code: openai
                    displayName: TEST openai
                models:
                  - modelId: TEST-stored
                    provider: openai
                    displayName: TEST stored
                    lifecycle:
                      status: ACTIVE
                    api:
                      outputTokenParam: MAX_TOKENS
                      temperature: true
                    pricing:
                      input: 1
                      output: %d
                      sourceUrl: https://example.invalid/pricing
                      observedAt: %s
                """.formatted(output, observedAt);
    }

    private static final String STORED = document(2, "2026-11-19");
    private static final String REPRICED = document(3, "2026-11-20");

    /** Answers every fetch with one result and records each URL it was asked for. */
    private static final class StubFetcher extends CatalogFetcher {
        final List<URI> asked = new ArrayList<>();
        final List<String> etags = new ArrayList<>();
        private final FetchResult answer;

        StubFetcher(FetchResult answer) {
            super(null);
            this.answer = answer;
        }

        @Override
        public FetchResult fetch(URI url, String etag) {
            asked.add(url);
            etags.add(etag);
            return answer;
        }
    }

    private final FakeStates states = new FakeStates();

    private LlmCatalogRefresh refresh(String url, CatalogFetcher fetcher) {
        LlmCatalogRefresh refresh = new LlmCatalogRefresh();
        refresh.catalog = CatalogTestDoubles.catalog(new FakeManualModels(), states, url);
        refresh.states = states;
        refresh.fetcher = fetcher;
        refresh.startup = Runnable::run;
        return refresh;
    }

    @Test
    void anHttpUrlIsRefusedBeforeAnyFetch() {
        StubFetcher fetcher = new StubFetcher(new CatalogFetcher.Fetched(STORED, null));

        assertEquals(LlmCatalogRefresh.RefreshOutcome.FAILED,
                refresh("http://example.invalid/catalog.yaml", fetcher).refreshNow());

        assertEquals(List.of(), fetcher.asked);
        assertEquals(List.of(LlmCatalogRefresh.URL_NOT_HTTPS), states.failures);
    }

    @Test
    void intervalOffStillReadsOnceAtStartup() {
        StubFetcher fetcher = new StubFetcher(new CatalogFetcher.NotModified());
        states.holding(STORED);

        refresh(REMOTE, fetcher).onStart(null);

        assertEquals(List.of(URI.create(REMOTE)), fetcher.asked);
    }

    /** onStart's own guard: with the URL none no first refresh is even scheduled. */
    @Test
    void urlNoneStartsNoRefresh() {
        List<Runnable> scheduled = new ArrayList<>();
        LlmCatalogRefresh refresh = refresh("none", new StubFetcher(new CatalogFetcher.Fetched(STORED, null)));
        refresh.startup = scheduled::add;

        refresh.onStart(null);

        assertEquals(List.of(), scheduled);
    }

    /** refreshNow's own guard: with the URL none a refresh ("Reload now") reads and writes nothing. */
    @Test
    void urlNoneNeverWritesTheRow() {
        StubFetcher fetcher = new StubFetcher(new CatalogFetcher.Fetched(STORED, null));

        assertEquals(LlmCatalogRefresh.RefreshOutcome.DISABLED, refresh("none", fetcher).refreshNow());

        assertEquals(List.of(), fetcher.asked);
        assertEquals(List.of(), states.stored);
        assertEquals(List.of(), states.failures, "not even url_not_https");
    }

    @Test
    void anInvalidFileKeepsTheCurrentCatalog() {
        states.holding(STORED);
        LlmCatalogRefresh refresh = refresh(REMOTE, new StubFetcher(new CatalogFetcher.Fetched("models: [", null)));
        String before = refresh.catalog.snapshot().sha256();

        assertEquals(LlmCatalogRefresh.RefreshOutcome.INVALID, refresh.refreshNow());

        assertEquals(before, refresh.catalog.snapshot().sha256());
        assertEquals(List.of(), states.stored);
        assertTrue(states.failures.getFirst().startsWith("invalid: "), states.failures.toString());
    }

    /** A validation message names text from the file; what is stored is flat and capped, whatever the file held. */
    @Test
    void anInvalidFilesErrorIsFlatAndCapped() {
        states.holding(STORED);
        String hostile = "providers: []\nmodels:\n  - modelId: \"TEST-a\nforged log line\r" + "x".repeat(500_000) + "\"\n"
                + "    provider: openai\n";
        LlmCatalogRefresh refresh = refresh(REMOTE, new StubFetcher(new CatalogFetcher.Fetched(hostile, null)));

        assertEquals(LlmCatalogRefresh.RefreshOutcome.INVALID, refresh.refreshNow());

        String error = states.failures.getFirst();
        assertTrue(error.startsWith("invalid: model TEST-a forged log line"), error);
        assertTrue(error.length() <= "invalid: ".length() + CatalogText.MAX_DETAIL, "length " + error.length());
        assertTrue(error.chars().noneMatch(Character::isISOControl), error);
    }

    @Test
    void aNewValidFileIsStoredAndSwapped() {
        states.holding(STORED);
        LlmCatalogRefresh refresh = refresh(REMOTE, new StubFetcher(new CatalogFetcher.Fetched(REPRICED, "\"TEST-2\"")));

        assertEquals(LlmCatalogRefresh.RefreshOutcome.REPLACED, refresh.refreshNow());

        LlmCatalogStateRepository.StoredDocument stored = states.stored.getLast();
        assertEquals(REPRICED, stored.document());
        assertEquals("remote", stored.source());
        assertEquals(REMOTE, stored.url());
        assertEquals("\"TEST-2\"", stored.etag());
        assertEquals(CatalogSnapshot.sha256Of(REPRICED), refresh.catalog.snapshot().sha256());
    }

    /** The listener installed a newer file while this refresh stored its own: the refresh must not undo it. */
    @Test
    void aNewerCatalogInstalledMidRefreshIsKept() throws Exception {
        CatalogSnapshot newer = LlmModelCatalog.parse(document(4, "2026-11-20"));
        AtomicReference<LlmModelCatalog> installedInto = new AtomicReference<>();
        FakeStates racing = new FakeStates() {
            @Override
            public void storeDocument(LlmCatalogStateRepository.StoredDocument doc) {
                super.storeDocument(doc);
                installedInto.get().replace(newer); // what the listener does on another instance's notice
            }
        };
        racing.holding(STORED);
        LlmCatalogRefresh refresh = new LlmCatalogRefresh();
        refresh.catalog = CatalogTestDoubles.catalog(new FakeManualModels(), racing, REMOTE);
        refresh.states = racing;
        refresh.fetcher = new StubFetcher(new CatalogFetcher.Fetched(REPRICED, null));
        installedInto.set(refresh.catalog);

        refresh.refreshNow();

        assertEquals(newer.sha256(), refresh.catalog.snapshot().sha256());
    }

    @Test
    void theSameShaOnlyRecordsTheCheck() {
        states.holding(STORED);
        LlmCatalogRefresh refresh = refresh(REMOTE, new StubFetcher(new CatalogFetcher.Fetched(STORED, "\"TEST-1\"")));

        assertEquals(LlmCatalogRefresh.RefreshOutcome.UNCHANGED, refresh.refreshNow());

        assertEquals(List.of(), states.stored);
        assertEquals(List.of("\"TEST-1\""), states.unchanged);
    }

    @Test
    void aNotModifiedAnswerSendsAndKeepsTheStoredEtag() {
        states.state = new LlmCatalogState(STORED, CatalogSnapshot.sha256Of(STORED), "\"TEST-1\"", "remote", REMOTE,
                null, null, null, null, null);
        StubFetcher fetcher = new StubFetcher(new CatalogFetcher.NotModified());

        assertEquals(LlmCatalogRefresh.RefreshOutcome.NOT_MODIFIED, refresh(REMOTE, fetcher).refreshNow());

        assertEquals(List.of("\"TEST-1\""), fetcher.etags);
        assertEquals(List.of("\"TEST-1\""), states.unchanged);
    }

    @Test
    void anEtagFromAnotherUrlIsNotSent() {
        states.state = new LlmCatalogState(STORED, CatalogSnapshot.sha256Of(STORED), "\"TEST-1\"", "remote",
                "https://example.invalid/other.yaml", null, null, null, null, null);
        StubFetcher fetcher = new StubFetcher(new CatalogFetcher.NotModified());

        refresh(REMOTE, fetcher).refreshNow();

        assertEquals(java.util.Arrays.asList((String) null), fetcher.etags);
    }

    @Test
    void aNewDatabaseStoresTheBundledCopyFirst() {
        LlmCatalogRefresh refresh = refresh(REMOTE, new StubFetcher(new CatalogFetcher.Failed("http_503")));

        assertEquals(LlmCatalogRefresh.RefreshOutcome.FAILED, refresh.refreshNow());

        assertEquals(1, states.stored.size());
        assertEquals("bundled", states.stored.getFirst().source());
        assertEquals(CatalogTestDoubles.testCatalogText(), states.stored.getFirst().document());
        assertEquals(List.of("http_503"), states.failures);
    }

    @Test
    void aReplacementRecordsTheChange() {
        states.holding(STORED);
        LlmCatalogRefresh refresh = refresh(REMOTE, new StubFetcher(new CatalogFetcher.Fetched(REPRICED, null)));

        refresh.refreshNow();

        String change = states.stored.getLast().lastChangeJson();
        assertTrue(change.contains("\"rePriced\":[{\"modelId\":\"TEST-stored\""), change);
        assertTrue(change.contains("\"before\":{\"INPUT\":1,\"OUTPUT\":2}"), change);
        assertTrue(change.contains("\"after\":{\"INPUT\":1,\"OUTPUT\":3}"), change);
        assertTrue(change.contains("\"oldSha\":\"" + CatalogSnapshot.sha256Of(STORED) + "\""), change);
    }
}
