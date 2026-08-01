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
import java.util.Collections;
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
                        ? Optional.of(Target.of(ref.kind(), ref.projectPath(), ref.number(), false))
                        : Optional.empty();
            }
            RepoRef repo = request.repo();
            if (request.scmType() != ScmType.GITLAB || repo == null) {
                return Optional.empty();
            }
            return GitLabIssueRefs.allows(projectAllowList, repo.full())
                    ? Optional.of(Target.of(ref.kind(), repo.full(), ref.number(), true))
                    : Optional.empty();
        });
    }

    /**
     * {@code path} is the project or group path an epic URL already named; for a project-relative
     * epic it is the project, whose ancestor groups the fetch tries in turn.
     *
     * @param pathIsAmbiguous whether {@code path} came from a project-relative reference. It matters
     *                        only for epics: a project path does not say which ancestor group owns an
     *                        epic, so that case must search outwards, whereas a URL names its group
     *                        exactly and must be used as given.
     */
    private record Target(GitLabIssueRefs.Kind kind, String path, int number, boolean pathIsAmbiguous) {

        /**
         * The project or group path is normalized to lower case here, at the single point every
         * {@link Target} is built — the same reasoning as the GitHub adapter's. GitLab forbids upper
         * case in a namespace path, so this cannot mangle a real project, but a reference written in
         * prose can carry any casing, and normalizing only the key would leave the fetch path to
         * whichever reference resolved first.
         */
        static Target of(GitLabIssueRefs.Kind kind, String path, int number, boolean pathIsAmbiguous) {
            return new Target(kind, path.toLowerCase(Locale.ROOT), number, pathIsAmbiguous);
        }

        /**
         * De-dup key. The kind is part of it because {@code #12}, {@code !12} and {@code &12} are
         * three different objects that share a number. {@code pathIsAmbiguous} is deliberately not
         * part of it: the same epic reached by a bare reference and by its URL is one epic, and must
         * still de-duplicate to a single fetch.
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
        return Optional.of(item(target, kind, sigil, object, body));
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
        for (String group : epicGroupCandidates(target)) {
            try {
                JsonNode epic = client.getJson(
                        "/api/v4/groups/" + GitLabIssueClient.encodePath(group) + "/epics/" + target.number());
                return Optional.of(item(target, KIND_EPIC, "&", epic, renderHead(epic)));
            } catch (GitLabIssueApiException e) {
                if (e.status() == 404 || e.status() == 403) {
                    continue; // wrong group, or epics not available on this tier
                }
                throw e;
            }
        }
        return Optional.empty();
    }

    /**
     * The groups to try for an epic, in order.
     *
     * <p>A project-relative {@code &7} does not say which ancestor group owns the epic, so search
     * outwards from the nearest — epic iids are scoped per group, so 7 can exist in both
     * {@code acme/tools} and {@code acme}, and a developer in {@code acme/tools/widgets} almost
     * always means the closer one. The project path itself goes last: a project is not a group, so
     * it only ever resolves for a single-segment path with no ancestors to search.
     *
     * <p>A URL names its group exactly, so it is the only candidate. Widening there would try the
     * parent of the group the author linked, and if that parent also had an epic with the same iid
     * the wrong epic would be returned as a success, with no error anywhere.
     */
    private static List<String> epicGroupCandidates(Target target) {
        if (!target.pathIsAmbiguous()) {
            return List.of(target.path());
        }
        List<String> groups = new ArrayList<>(GitLabIssueRefs.ancestorGroups(target.path()));
        groups.add(target.path());
        return groups;
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

    /**
     * {@code target.pathIsAmbiguous()} is exactly "the reference did not name its own project or
     * group" — a bare reference borrowing {@code request.repo()}. When it is false, the reference
     * already named its project/group explicitly, so the rendered title leads with that qualified
     * form rather than the bare sigil.
     *
     * <p>This matters because a title re-mined at level 2 of the two-level fetch ({@code
     * ContextWorker}) would otherwise present a bare {@code #12}/{@code !34}/{@code &7} as fresh and
     * resolve it against the review's own project — wrong whenever the target project differs. A
     * qualified rendering normalizes back into the already-seen set instead.
     *
     * <p><b>Note the two forms are safe for different reasons.</b> A qualified issue reference
     * ({@code group/project#12}) round-trips: {@link GitLabIssueRefs} recognises it, so it normalizes
     * to what {@code seen} already holds. A qualified merge request or epic ({@code group/project!34},
     * {@code group/project&7}) does not — there is no qualified prose grammar for {@code !} or
     * {@code &}, only the bare and URL forms — so it is simply never extracted. Both reach the same
     * outcome today, but the second rests on a grammar that does not exist: adding a qualified
     * {@code !}/{@code &} pattern would make these titles round-trip as fresh references again, so
     * that change must come with a normalization that maps them into the seen set.
     */
    private static ContextItem item(Target target, String kind, String sigil, JsonNode object,
                                    StringBuilder body) {
        String title = object.path("title").asText("");
        String uri = object.path("web_url").asText("");
        String ref = target.pathIsAmbiguous() ? sigil + target.number()
                : target.path() + sigil + target.number();
        return new ContextItem(kind, ref + (title.isBlank() ? "" : " — " + title),
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
     *
     * <p>This endpoint returns notes NEWEST-first, so the newest are the head of page 1 — the sort is
     * requested explicitly rather than left to the API's default, because the whole of "recent"
     * depends on it. They are then reversed back into reading order.
     */
    private void appendRecentNotes(StringBuilder body, String notesPath) {
        JsonNode notes;
        try {
            notes = client.getJson(notesPath + "?per_page=100&order_by=created_at&sort=desc");
        } catch (RuntimeException e) {
            return;
        }
        if (!notes.isArray() || notes.isEmpty()) {
            return;
        }
        List<JsonNode> human = new ArrayList<>();
        for (JsonNode note : notes) {
            if (!note.path("system").asBoolean(false) && human.size() < MAX_COMMENTS) {
                human.add(note);
            }
        }
        Collections.reverse(human); // newest-first off the wire, oldest-first for the prompt
        StringBuilder rendered = new StringBuilder();
        for (JsonNode note : human) {
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
