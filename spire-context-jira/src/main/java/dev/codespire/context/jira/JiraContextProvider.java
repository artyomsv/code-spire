package dev.codespire.context.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the PR's referenced Jira issue keys into {@link ContextItem}s for the
 * review prompt. Built per {@code GatherContext} command from the brokered
 * credential (like the SCM adapters), NOT a long-lived CDI singleton — the
 * credential is workspace-scoped and decrypted only inside the worker.
 *
 * <p>Retrieved ticket text is UNTRUSTED (SECURITY.md) — the prompt builder fences
 * it; this provider only shapes it. One bad key (404/typo) is skipped, not fatal;
 * an auth failure yields an {@code ERROR} contribution so the aggregator records
 * the miss without aborting the review.
 */
public class JiraContextProvider implements ContextProvider {

    public static final String SOURCE = "JIRA";
    private static final String KIND = "JIRA_TICKET";
    /** Guard one oversized ticket from dominating the shared context budget. */
    private static final int MAX_DESCRIPTION_CHARS = 4_000;
    /** Include the last few comments — where the real decisions often live — bounded to limit noise/tokens. */
    private static final int MAX_COMMENTS = 5;
    private static final int MAX_COMMENT_CHARS = 500;

    private final JiraClient client;
    private final String siteBaseUrl;
    private final java.util.Set<String> projectKeys;

    public JiraContextProvider(JiraConfig config, ObjectMapper mapper) {
        this.client = new JiraClient(config, mapper);
        this.siteBaseUrl = config.baseUrl().replaceAll("/$", "");
        this.projectKeys = config.projectKeys();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request.ticketKeys() != null && !matchingKeys(request).isEmpty();
    }

    @Override
    public CompletionStage<ContextContribution> contribute(ContextRequest request) {
        return CompletableFuture.supplyAsync(() -> fetch(request));
    }

    /** The request's candidate keys narrowed to the configured project keys (all when unconfigured). */
    private java.util.Set<String> matchingKeys(ContextRequest request) {
        java.util.Set<String> keys = request.ticketKeys();
        return keys == null ? java.util.Set.of() : JiraTicketKeys.filter(keys, projectKeys);
    }

    private ContextContribution fetch(ContextRequest request) {
        long start = System.nanoTime();
        List<ContextItem> items = new ArrayList<>();
        try {
            for (String key : matchingKeys(request)) {
                resolve(key).ifPresent(items::add);
            }
        } catch (JiraApiException e) {
            // Auth/config failure applies to every key — record ERROR, don't abort the review.
            return new ContextContribution(SOURCE, ContribStatus.ERROR, List.of(), latencyMs(start));
        }
        ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
        return new ContextContribution(SOURCE, status, items, latencyMs(start));
    }

    /** @return the ticket as a ContextItem, or empty when the key does not resolve (404). */
    private java.util.Optional<ContextItem> resolve(String key) {
        JsonNode issue;
        try {
            // `comment` rides the same call — the last few comments carry decisions the diff can't show.
            issue = client.getJson("/rest/api/2/issue/" + key
                    + "?fields=summary,description,status,issuetype,comment");
        } catch (JiraApiException e) {
            if (e.status() == 404) {
                return java.util.Optional.empty(); // typo'd or unreachable key — skip, keep the rest
            }
            throw e; // auth/5xx bubbles up to mark the whole contribution
        }
        JsonNode fields = issue.path("fields");
        String summary = fields.path("summary").asText("");
        String status = fields.path("status").path("name").asText("");
        String type = fields.path("issuetype").path("name").asText("");
        String description = clip(fields.path("description").asText(""), MAX_DESCRIPTION_CHARS);

        StringBuilder body = new StringBuilder();
        if (!type.isBlank() || !status.isBlank()) {
            body.append("Type: ").append(type.isBlank() ? "?" : type)
                    .append(" | Status: ").append(status.isBlank() ? "?" : status).append('\n');
        }
        if (!description.isBlank()) {
            body.append('\n').append(description);
        }
        appendRecentComments(body, fields.path("comment").path("comments"));
        String title = key + (summary.isBlank() ? "" : " — " + summary);
        return java.util.Optional.of(new ContextItem(KIND, title, body.toString().strip(),
                siteBaseUrl + "/browse/" + key));
    }

    /**
     * Append the most recent comments (the field returns them oldest-first, so we take the tail) with the
     * author and a truncated body — bounded to {@link #MAX_COMMENTS} so a chatty ticket can't crowd out the diff.
     */
    private void appendRecentComments(StringBuilder body, JsonNode comments) {
        if (!comments.isArray() || comments.isEmpty()) {
            return;
        }
        int from = Math.max(0, comments.size() - MAX_COMMENTS);
        StringBuilder rendered = new StringBuilder();
        for (int i = from; i < comments.size(); i++) {
            JsonNode c = comments.get(i);
            String text = clip(c.path("body").asText(""), MAX_COMMENT_CHARS);
            if (text.isBlank()) {
                continue;
            }
            String author = c.path("author").path("displayName").asText("");
            rendered.append("\n- ").append(author.isBlank() ? "" : author + ": ").append(text);
        }
        if (rendered.length() > 0) {
            body.append("\n\nRecent comments:").append(rendered);
        }
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
