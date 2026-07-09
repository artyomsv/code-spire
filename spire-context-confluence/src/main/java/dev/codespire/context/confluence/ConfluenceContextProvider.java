package dev.codespire.context.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the Confluence page links referenced in the PR into {@link ContextItem}s
 * for the review prompt — the second {@link ContextProvider} on the SPI, link-driven
 * where {@code JiraContextProvider} is ticket-key-driven (EVENT-MODEL S4). Built per
 * {@code GatherContext} command from the brokered credential, NOT a long-lived CDI
 * singleton — the credential is workspace-scoped and decrypted only inside the worker.
 *
 * <p>Retrieved page text is UNTRUSTED (SECURITY.md) — the prompt builder fences it;
 * this provider only strips the storage-format markup and shapes it. One bad link
 * (404/typo) is skipped, not fatal; an auth failure yields an {@code ERROR}
 * contribution so the aggregator records the miss without aborting the review.
 */
public class ConfluenceContextProvider implements ContextProvider {

    public static final String SOURCE = "CONFLUENCE";
    private static final String KIND = "CONFLUENCE_PAGE";
    /** Guard one oversized page from dominating the shared context budget. */
    private static final int MAX_BODY_CHARS = 4_000;
    /** Bound the fan-out per PR — a description can link many pages; keep the diff dominant. */
    private static final int MAX_PAGES = 5;

    private final ConfluenceClient client;
    private final String siteBaseUrl;
    private final Set<String> spaceKeys;

    public ConfluenceContextProvider(ConfluenceConfig config, ObjectMapper mapper) {
        this.client = new ConfluenceClient(config, mapper);
        this.siteBaseUrl = config.baseUrl().replaceAll("/$", "");
        this.spaceKeys = config.spaceKeys();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request.links() != null && !matchingPageIds(request).isEmpty();
    }

    @Override
    public CompletionStage<ContextContribution> contribute(ContextRequest request) {
        return CompletableFuture.supplyAsync(() -> fetch(request));
    }

    /** The request's candidate links narrowed to page ids on the configured Confluence host, capped. */
    private List<String> matchingPageIds(ContextRequest request) {
        return ConfluenceLinks.pageIds(request.links(), siteBaseUrl).stream().limit(MAX_PAGES).toList();
    }

    private ContextContribution fetch(ContextRequest request) {
        long start = System.nanoTime();
        List<ContextItem> items = new ArrayList<>();
        try {
            for (String pageId : matchingPageIds(request)) {
                resolve(pageId).ifPresent(items::add);
            }
        } catch (ConfluenceApiException e) {
            // Auth/config failure applies to every page — record ERROR, don't abort the review.
            return new ContextContribution(SOURCE, ContribStatus.ERROR, List.of(), latencyMs(start));
        }
        ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
        return new ContextContribution(SOURCE, status, items, latencyMs(start));
    }

    /** @return the page as a ContextItem, or empty when it does not resolve (404) or is filtered out by space. */
    private Optional<ContextItem> resolve(String pageId) {
        JsonNode page;
        try {
            page = client.getJson("/rest/api/content/" + pageId + "?expand=body.storage,space,version");
        } catch (ConfluenceApiException e) {
            if (e.status() == 404) {
                return Optional.empty(); // typo'd or unreachable page — skip, keep the rest
            }
            throw e; // auth/5xx bubbles up to mark the whole contribution
        }
        String spaceKey = page.path("space").path("key").asText("");
        if (!spaceKeys.isEmpty() && !spaceKeys.contains(spaceKey.toUpperCase())) {
            return Optional.empty(); // outside the configured space allow-list
        }
        String pageTitle = page.path("title").asText("");
        String spaceName = page.path("space").path("name").asText("");
        String body = ConfluenceHtml.toText(page.path("body").path("storage").path("value").asText(""));
        body = clip(body, MAX_BODY_CHARS);

        String title = pageTitle.isBlank() ? "Confluence page " + pageId : pageTitle;
        if (!spaceName.isBlank()) {
            title = title + " (" + spaceName + ")";
        }
        return Optional.of(new ContextItem(KIND, title, body, webUrl(page, pageId)));
    }

    /** The page's human URL: the response's {@code _links.webui} onto the site base, else a viewpage link. */
    private String webUrl(JsonNode page, String pageId) {
        String webui = page.path("_links").path("webui").asText("");
        if (!webui.isBlank()) {
            return siteBaseUrl + (webui.startsWith("/") ? webui : "/" + webui);
        }
        return siteBaseUrl + "/pages/viewpage.action?pageId=" + pageId;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static long latencyMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
