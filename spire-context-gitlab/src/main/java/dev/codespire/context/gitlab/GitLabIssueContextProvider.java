package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the MR's referenced GitLab issues, merge requests and epics into {@link ContextItem}s for
 * the review prompt. Built per {@code GatherContext} command from the brokered credential (like the
 * SCM adapters), NOT a long-lived singleton.
 *
 * <p>Retrieved text is UNTRUSTED (SECURITY.md) — the prompt builder fences it; this provider only
 * shapes it. One bad reference (404, or an epic on a non-Premium instance) is skipped, not fatal; an
 * auth failure yields an {@code ERROR} contribution so the aggregator records the miss without
 * aborting the review.
 */
public class GitLabIssueContextProvider implements ContextProvider {

    public static final String SOURCE = "GITLAB_ISSUES";
    private static final String KIND_ISSUE = "ISSUE";
    /** The house-neutral term for a change request, so core's kind list gains no platform vocabulary. */
    private static final String KIND_PULL_REQUEST = "PULL_REQUEST";
    private static final String KIND_EPIC = "EPIC";
    private static final int MAX_BODY_CHARS = 4_000;
    private static final int MAX_COMMENTS = 5;
    private static final int MAX_COMMENT_CHARS = 500;
    private static final int MAX_REFERENCES = 10;

    private final GitLabIssueClient client;
    private final Set<String> projectAllowList;

    public GitLabIssueContextProvider(GitLabIssueConfig config, ObjectMapper mapper) {
        this.client = new GitLabIssueClient(config, mapper);
        this.projectAllowList = config.projectAllowList();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return !resolvable(request).isEmpty();
    }

    @Override
    public CompletionStage<ContextContribution> contribute(ContextRequest request) {
        return CompletableFuture.supplyAsync(() -> fetch(request));
    }

    /** The request's candidates narrowed to what this provider can resolve, order-preserving and capped. */
    private List<Target> resolvable(ContextRequest request) {
        Set<String> references = request.references();
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<Target> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String reference : references) {
            if (targets.size() >= MAX_REFERENCES) {
                break;
            }
            resolve(reference, request).filter(t -> seen.add(t.key())).ifPresent(targets::add);
        }
        return targets;
    }

    /**
     * A project-relative reference borrows the review's own project, which is only the right project
     * when the review runs on THIS platform — the same path exists on other hosts. A qualified
     * reference or URL names its own project and needs no such guard.
     */
    private Optional<Target> resolve(String reference, ContextRequest request) {
        return GitLabIssueRefs.parse(reference).flatMap(ref -> {
            if (!ref.isProjectRelative()) {
                return GitLabIssueRefs.allows(projectAllowList, ref.projectPath())
                        ? Optional.of(Target.of(ref.kind(), ref.projectPath(), ref.number()))
                        : Optional.empty();
            }
            RepoRef repo = request.repo();
            if (request.scmType() != ScmType.GITLAB || repo == null) {
                return Optional.empty();
            }
            return GitLabIssueRefs.allows(projectAllowList, repo.full())
                    ? Optional.of(Target.of(ref.kind(), repo.full(), ref.number()))
                    : Optional.empty();
        });
    }

    /**
     * {@code path} is the project or group path an epic URL already named; for a project-relative
     * epic it is the project, whose ancestor groups the fetch tries in turn.
     */
    private record Target(GitLabIssueRefs.Kind kind, String path, int number) {

        /**
         * The project or group path is normalized to lower case here, at the single point every
         * {@link Target} is built — the same reasoning as the GitHub adapter's. GitLab forbids upper
         * case in a namespace path, so this cannot mangle a real project, but a reference written in
         * prose can carry any casing, and normalizing only the key would leave the fetch path to
         * whichever reference resolved first.
         */
        static Target of(GitLabIssueRefs.Kind kind, String path, int number) {
            return new Target(kind, path.toLowerCase(Locale.ROOT), number);
        }

        /**
         * De-dup key. The kind is part of it because {@code #12}, {@code !12} and {@code &12} are
         * three different objects that share a number.
         */
        String key() {
            return kind + ":" + path + "#" + number;
        }
    }

    private ContextContribution fetch(ContextRequest request) {
        long start = System.nanoTime();
        List<ContextItem> items = new ArrayList<>();
        try {
            for (Target target : resolvable(request)) {
                resolveItem(target).ifPresent(items::add);
            }
        } catch (GitLabIssueApiException e) {
            return new ContextContribution(SOURCE, ContribStatus.ERROR, List.of(), latencyMs(start));
        }
        ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
        return new ContextContribution(SOURCE, status, items, latencyMs(start));
    }

    private Optional<ContextItem> resolveItem(Target target) {
        return switch (target.kind()) {
            case ISSUE -> fetchProjectObject(target, "issues", KIND_ISSUE, "#");
            case MERGE_REQUEST -> fetchProjectObject(target, "merge_requests", KIND_PULL_REQUEST, "!");
            case EPIC -> fetchEpic(target);
        };
    }

    /** An issue or merge request: same JSON shape, same notes endpoint, different path segment. */
    private Optional<ContextItem> fetchProjectObject(Target target, String segment, String kind,
                                                     String sigil) {
        String base = "/api/v4/projects/" + GitLabIssueClient.encodePath(target.path())
                + "/" + segment + "/" + target.number();
        JsonNode object;
        try {
            object = client.getJson(base);
        } catch (GitLabIssueApiException e) {
            if (e.status() == 404) {
                return Optional.empty(); // typo'd or unreachable — skip, keep the rest
            }
            throw e;
        }
        StringBuilder body = renderHead(object);
        appendRecentNotes(body, base + "/notes");
        return Optional.of(item(kind, sigil, target.number(), object, body));
    }

    /**
     * An epic lives at group level, and the project path does not say which ancestor owns it — so try
     * the nearest ancestor first, then outward.
     *
     * <p>Epics are a GitLab Premium feature: a free-tier instance answers 403 or 404 for every group.
     * That skips the epic and nothing else, because an operator who cannot read epics must still get
     * their issue context. An epic has no notes endpoint in this flow — the description is the value.
     */
    private Optional<ContextItem> fetchEpic(Target target) {
        List<String> groups = new ArrayList<>();
        if (target.path().contains("/")) {
            groups.addAll(GitLabIssueRefs.ancestorGroups(target.path()));
        }
        // An epic URL names its group directly, so that path is itself a candidate.
        groups.add(target.path());
        for (String group : groups) {
            try {
                JsonNode epic = client.getJson(
                        "/api/v4/groups/" + GitLabIssueClient.encodePath(group) + "/epics/" + target.number());
                return Optional.of(item(KIND_EPIC, "&", target.number(), epic, renderHead(epic)));
            } catch (GitLabIssueApiException e) {
                if (e.status() == 404 || e.status() == 403) {
                    continue; // wrong group, or epics not available on this tier
                }
                throw e;
            }
        }
        return Optional.empty();
    }

    /** State plus labels, then the description — the same head shape every kind renders. */
    private static StringBuilder renderHead(JsonNode object) {
        StringBuilder body = new StringBuilder();
        String state = object.path("state").asText("");
        body.append("State: ").append(state.isBlank() ? "?" : state);
        String labels = labelsOf(object);
        if (!labels.isBlank()) {
            body.append(" | Labels: ").append(labels);
        }
        body.append('\n');
        String description = clip(object.path("description").asText(""), MAX_BODY_CHARS);
        if (!description.isBlank()) {
            body.append('\n').append(description);
        }
        return body;
    }

    private static ContextItem item(String kind, String sigil, int number, JsonNode object,
                                    StringBuilder body) {
        String title = object.path("title").asText("");
        String uri = object.path("web_url").asText("");
        return new ContextItem(kind, sigil + number + (title.isBlank() ? "" : " — " + title),
                body.toString().strip(), uri.isBlank() ? null : uri);
    }

    /** GitLab returns labels as a plain string array, unlike the objects GitHub returns. */
    private static String labelsOf(JsonNode object) {
        JsonNode labels = object.path("labels");
        if (!labels.isArray() || labels.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode label : labels) {
            String name = label.asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return String.join(", ", names);
    }

    /**
     * Append the most recent human notes. {@code system: true} notes are activity records ("changed
     * title from…") — noise that would crowd out the discussion. Notes are a second call and pure
     * enrichment, so a failure drops them and keeps the object.
     */
    private void appendRecentNotes(StringBuilder body, String notesPath) {
        JsonNode notes;
        try {
            notes = client.getJson(notesPath + "?per_page=100");
        } catch (RuntimeException e) {
            return;
        }
        if (!notes.isArray() || notes.isEmpty()) {
            return;
        }
        List<JsonNode> human = new ArrayList<>();
        for (JsonNode note : notes) {
            if (!note.path("system").asBoolean(false)) {
                human.add(note);
            }
        }
        int from = Math.max(0, human.size() - MAX_COMMENTS);
        StringBuilder rendered = new StringBuilder();
        for (int i = from; i < human.size(); i++) {
            JsonNode note = human.get(i);
            String text = clip(note.path("body").asText(""), MAX_COMMENT_CHARS);
            if (text.isBlank()) {
                continue;
            }
            String author = note.path("author").path("username").asText("");
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
