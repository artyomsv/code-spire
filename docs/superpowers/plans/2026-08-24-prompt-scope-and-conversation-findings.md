# Per-repo prompts and conversation-derived findings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close ROADMAP items E16 (per-repo prompt scope, preview against a real review, default-migration story) and E17 (findings raised from a conversation via `/finding`), after first normalizing slash-command routing across the three SCM ingresses — which E17 depends on and which today hides two live defects.

**Architecture:** `/finding` rides the existing `ManualCommandReceived` path, which gains a thread ref and a location so the command can reach the thread it was typed in. The finding is appended by the aggregate (ADR-010) carrying anchor + severity only, with its message written to the encrypted read model — landing in `open_findings_json`, the ADR-019 carry-forward baseline, so reconciliation and exclusion work with no new plumbing. Prompt templates re-key from `kind` to `(scope, kind)` for a repo→global→built-in resolution, store the built-in default as it stood at save time so drift is computable, and gain a preview that renders a **real** review through the production renderer.

**Tech Stack:** Java 25 / Quarkus 3.38.3 / Gradle Kotlin DSL; Postgres + Flyway; Kafka (Redpanda) with polymorphic JSON wire types; React 19 + Vite + vitest.

**Spec:** `docs/superpowers/specs/2026-08-24-prompt-scope-and-conversation-findings-design.md`

## Global Constraints

- **4-space indent for Java, 2-space for TypeScript.** Explicit types over `var` in Java; `interface` over `type` for TS object shapes.
- **`spire-contract` and `spire-diff` stay framework-free.** Only the JDK, those modules, and `jackson-annotations` (annotations only). Enforced by `PureModulesAreFrameworkFreeTest` in `spire-arch`.
- **No provider name in a core module** (`spire-contract`, `spire-orchestrator`, `spire-review-worker`, `spire-gateway`) outside the `spire-arch` allowlist. Enforced by source-text scan; comments are exempt.
- **No fabricated data anywhere a user can see it.** Test fixtures use `example.invalid`, `TEST-`/`CANARY-` prefixes, obviously-synthetic values. No plausible-looking sample diffs.
- **Money in millicents.** Host-exposed dev ports in the 34xxx range.
- **Never call the project "open source"** — source-available, licensed per module (ADR-021). No Apache-2.0 module may depend on a service module.
- **Commit subjects imperative, ≤72 chars; body lines wrapped at 72.** Never mention AI/agentic authoring, model names, or vendor names in commit or PR messages.
- **Sensitive fields are Tink-encrypted at rest** with AAD = reviewId for review data.
- **Verification commands:** `./gradlew testFast` (13 Docker-free modules, ~25s), `./gradlew testServices` (the 3 deployables, Testcontainers), `cd spire-ui && npm test`, `cd spire-ui && npx tsc --noEmit`.
- **Baseline before starting:** 1256 Java tests / 166 suites; 323 vitest / 45 files.

## Phase map

| Phase | Tasks | Ships independently? |
|---|---|---|
| 1 — Command routing normalization | 1–6 | Yes; fixes two live defects on its own |
| 2 — E17 conversation-derived findings | 7–13 | Yes; depends on Phase 1 |
| 3 — Preview against a real review | 14–16 | Yes; independent of 1–2 |
| 4 — Default-migration story | 17–19 | Yes; independent |
| 5 — Per-repo prompt scope | 20–23 | Yes; cut first if trimming |

Phases 3, 4 and 5 depend on nothing in 1–2. If the first pull request grows too large, lift them onto their own branches — each ends in shippable software.

## File structure

**Created**

| File | Responsibility |
|---|---|
| `spire-gateway/src/main/java/dev/codespire/gateway/WebhookCommands.java` | The one list of recognised slash commands, composed from each endpoint's constant |
| `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ConversationFindings.java` | Parse `/finding` args, resolve its anchor, decide refuse-vs-file |
| `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptSampleRenderer.java` | Render a candidate template against a real review's diff + context |
| `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptScope.java` | The `'*'`-or-`workspace/slug` scope value and its parsing/validation |
| `spire-orchestrator/src/main/resources/db/migration/V33__prompt_template_base.sql` | Ancestor columns for drift detection |
| `spire-orchestrator/src/main/resources/db/migration/V34__prompt_template_scope.sql` | Composite `(scope, kind)` key |
| `spire-ui/src/components/PromptScopePicker.tsx` | Scope selector for Settings → Prompts |
| `spire-ui/src/components/PromptSamplePicker.tsx` | Review picker for the real-review preview |

**Modified (principal)**

| File | Change |
|---|---|
| `spire-contract/.../event/IntegrationEvent.java` | `ManualCommandReceived` gains `threadRef`, `location` |
| `spire-contract/.../command/RecordCommand.java` | `RaiseConversationFinding` |
| `spire-contract/.../event/DomainEvent.java` | `ConversationFindingRaised` |
| `spire-contract/.../command/ActionCommand.java` | `ConfirmFinding` |
| `spire-contract/.../lifecycle/ReviewState.java` | `raisedFindingComments` idempotency set |
| `spire-contract/.../lifecycle/ReviewLifecycle.java` | decide + evolve for the new pair |
| `spire-scm-github/.../GitHubIngress.java` | Command check on `reviewCommentReply`; thread context onto the command |
| `spire-scm-gitlab/.../GitLabIngress.java` | Thread context onto the command; unrecognised `/foo` falls through |
| `spire-scm-bitbucket/.../BitbucketCloudIngress.java` | Thread context computed before the command branch |
| `spire-orchestrator/.../pipeline/IntegrationSaga.java` | `/finding` branch |
| `spire-orchestrator/.../pipeline/DomainEventSink.java` | Project `ConversationFindingRaised` |
| `spire-orchestrator/.../readmodel/ReviewProjection.java` | `addConversationFinding`; `FindingView.origin` |
| `spire-orchestrator/.../readmodel/ReviewDetail.java` | `FindingView` gains `origin` |
| `spire-orchestrator/.../prompt/PromptRegistry.java` | Scope-aware resolution; ancestor tracking |
| `spire-orchestrator/.../prompt/PromptResource.java` | `scope` param; `reviewId` on preview |
| `spire-orchestrator/.../prompt/PromptView.java` | `scope`, `inheritedFrom`, `defaultDrifted`, `baseKnown` |
| `spire-orchestrator/.../prompt/WorkerPromptTemplates.java` | `forKind(kind, repo)` |
| `spire-review-worker/.../pipeline/FollowUpWorker.java` | `confirmFinding`; turn-cap notice clause |
| `spire-review-worker/.../pipeline/CommandDispatcher.java` | `ConfirmFinding` case |
| `spire-ui/src/api.ts` | `Finding.origin`; prompt scope + sample preview + drift types |
| `spire-ui/src/render.tsx` | Origin badge on a finding |
| `spire-ui/src/components/PromptDetail.tsx` | Scope, drift banner, sample preview |
| `spire-ui/src/components/PromptsSettings.tsx` | Scope picker, drift badges |

---

# Phase 1 — Command routing normalization

### Task 1: `ManualCommandReceived` carries thread context

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java:74-76`
- Test: `spire-contract/src/test/java/dev/codespire/contract/event/IntegrationEventWireTest.java`

**Interfaces:**
- Consumes: `ThreadRef(String value)`, `ThreadLocation(String path, int line)` with static `ThreadLocation.of(String, Integer)` returning null unless both parts present — both already in `dev.codespire.contract.scm`.
- Produces: `ManualCommandReceived(RepoRef repo, long prId, String command, String args, Author author, ThreadRef threadRef, ThreadLocation location)` plus a 5-arg constructor delegating with `(null, null)`.

- [ ] **Step 1: Write the failing test**

Add to `IntegrationEventWireTest`:

```java
@Test
void manualCommandCarriesThreadContextAcrossTheWire() throws Exception {
    ManualCommandReceived original = new ManualCommandReceived(
            new RepoRef("acme", "widgets"), 7, "finding", "major shadows the field",
            new Author("u-1", "octocat"),
            new ThreadRef("thread-9"), new ThreadLocation("src/Foo.java", 44));

    String json = mapper.writeValueAsString(original);
    IntegrationEvent back = mapper.readValue(json, IntegrationEvent.class);

    assertThat(back).isEqualTo(original);
}

@Test
void manualCommandWithoutThreadContextStillDeserializes() throws Exception {
    // The shape every command on the wire has today: no threadRef, no location.
    String legacy = """
            {"type":"ManualCommandReceived","repo":{"workspace":"acme","slug":"widgets"},
             "prId":7,"command":"review","args":"",
             "author":{"providerUserId":"u-1","username":"octocat"}}""";

    ManualCommandReceived back = (ManualCommandReceived) mapper.readValue(legacy, IntegrationEvent.class);

    assertThat(back.threadRef()).isNull();
    assertThat(back.location()).isNull();
    assertThat(back.command()).isEqualTo("review");
}
```

Check the existing test file for the exact `Author` and `RepoRef` constructor arity and the mapper field name before writing; match what its neighbours use.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-contract:test --tests '*IntegrationEventWireTest*'`
Expected: FAIL — constructor `ManualCommandReceived` cannot be applied to 7 arguments.

- [ ] **Step 3: Add the components**

```java
    /**
     * A {@code /command} typed on a pull request.
     *
     * <p>{@code threadRef} and {@code location} are the thread the command was typed in, when it was
     * typed in one — null for a top-level comment, which is every command that existed before
     * {@code /finding}. They are carried because a command that acts on a thread cannot find its
     * thread otherwise: two of the three ingresses used to check for {@code /} before computing
     * either, and discarded the context the command needed.
     */
    record ManualCommandReceived(RepoRef repo, long prId, String command, String args,
                                 Author author, ThreadRef threadRef, ThreadLocation location)
            implements IntegrationEvent {

        // Without thread context — a top-level command. Kept so every existing call site and every
        // record already on the wire keeps working (the same additive treatment AuthorReplied took
        // when it grew mentions, then location).
        public ManualCommandReceived(RepoRef repo, long prId, String command, String args, Author author) {
            this(repo, prId, command, args, author, null, null);
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-contract:test --tests '*IntegrationEventWireTest*'`
Expected: PASS, both tests.

- [ ] **Step 5: Run the contract-compat gate**

Run: `./gradlew :spire-contract:test`
Expected: PASS including `ContractSchemaSnapshotTest`. If the snapshot fails, update the golden file — this is an additive change with a retained constructor, so it is compatible. Record in the commit body that the snapshot was regenerated and why.

Note the documented limitation in `techdebt/spire-contract/3-2-…`: that snapshot renders a nested record component as `name: TypeName` and does **not** recurse. It covers this top-level change; do not read a green snapshot as approval of more than it checks.

- [ ] **Step 6: Commit**

```bash
git add spire-contract/
git commit -m "Carry a command's thread context on ManualCommandReceived"
```

---

### Task 2: GitHub — a slash command in an inline thread is a command

This task fixes a live defect: `/review` typed in a GitHub inline review thread is currently answered as a *question* by the LLM instead of forcing a re-review, because `reviewCommentReply` has no `/` check while `issueComment` does.

**Files:**
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubIngress.java` (`issueComment` ~line 180, `reviewCommentReply` ~line 211)
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubIngressTest.java`

**Interfaces:**
- Consumes: `ManualCommandReceived(..., ThreadRef, ThreadLocation)` from Task 1.
- Produces: nothing new; behaviour only.

- [ ] **Step 1: Write the failing test**

```java
@Test
void slashCommandInAnInlineThreadIsACommandNotAQuestion() {
    // GitHub delivers an inline reply on pull_request_review_comment, a different webhook from
    // issue_comment. Only the latter used to check for "/", so "/review" on a line was answered
    // by the model as a question — and paid for an LLM call to do it.
    String payload = """
            {"action":"created",
             "repository":{"owner":{"login":"acme"},"name":"widgets"},
             "pull_request":{"number":7},
             "comment":{"id":901,"in_reply_to_id":900,"body":"/review",
                        "path":"src/Foo.java","line":44,
                        "user":{"id":11,"login":"octocat"}}}""";

    List<IntegrationEvent> events = ingress.translate(headers("pull_request_review_comment"), payload);

    assertThat(events).singleElement().isInstanceOfSatisfying(ManualCommandReceived.class, e -> {
        assertThat(e.command()).isEqualTo("review");
        assertThat(e.threadRef()).isEqualTo(new ThreadRef("900"));
        assertThat(e.location()).isEqualTo(new ThreadLocation("src/Foo.java", 44));
    });
}

@Test
void anUnrecognisedSlashWordInAnInlineThreadStaysAComment() {
    String payload = """
            {"action":"created",
             "repository":{"owner":{"login":"acme"},"name":"widgets"},
             "pull_request":{"number":7},
             "comment":{"id":901,"in_reply_to_id":900,"body":"/usr/bin/env is on PATH here",
                        "path":"src/Foo.java","line":44,
                        "user":{"id":11,"login":"octocat"}}}""";

    List<IntegrationEvent> events = ingress.translate(headers("pull_request_review_comment"), payload);

    assertThat(events).singleElement().isInstanceOf(AuthorReplied.class);
}
```

Read the existing tests in this file first: copy their exact fixture-building helper (`headers(...)`, the ingress field name, how signatures are supplied) rather than inventing one. The payload shapes above must match what the file's other `pull_request_review_comment` tests use.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-scm-github:test --tests '*GitHubIngressTest*'`
Expected: FAIL — the first test gets `AuthorReplied` where `ManualCommandReceived` was expected.

- [ ] **Step 3: Extract the shared parse and apply it to both surfaces**

Add to `GitHubIngress`:

```java
    /**
     * The command a comment carries, or null when it is not one. A body that starts with "/" but
     * whose first word is not a recognised command is NOT a command — it is a comment that happens
     * to begin with a slash (a path, a fraction), and it goes on to engage the bot normally.
     */
    private ParsedCommand parseCommand(String text) {
        if (!text.startsWith("/")) {
            return null;
        }
        String[] parts = text.substring(1).split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        return commands.contains(command)
                ? new ParsedCommand(command, parts.length > 1 ? parts[1] : "")
                : null;
    }

    private record ParsedCommand(String command, String args) {
    }
```

In `issueComment`, replace the inline `startsWith`/`split`/`contains` block with:

```java
        ParsedCommand parsed = parseCommand(text);
        if (parsed != null) {
            return List.of(new ManualCommandReceived(repo, issueNumber, parsed.command(), parsed.args(),
                    author(comment.path("user")), null, null));
        }
        String commentId = comment.path("id").asText();
        return List.of(new AuthorReplied(repo, issueNumber, ReviewIds.reviewId(repo, issueNumber),
                new ThreadRef(commentId), commentId, text, author(comment.path("user")), true,
                mentions(text)));
```

In `reviewCommentReply`, immediately after the existing `text`/`threadRef`/`location` values are computed and before the `AuthorReplied` return, add:

```java
        ParsedCommand parsed = parseCommand(text);
        if (parsed != null) {
            return List.of(new ManualCommandReceived(repo, prNumber, parsed.command(), parsed.args(),
                    author(comment.path("user")), threadRef, location));
        }
```

Use whatever local variable names `reviewCommentReply` already computes for the thread root, PR number and location — read the method before editing and reuse them rather than introducing parallel names.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-scm-github:test`
Expected: PASS, all tests in the module.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-github/
git commit -F - <<'EOF'
Treat a slash command in a GitHub inline thread as a command

GitHub delivers an inline reply on pull_request_review_comment and a
top-level comment on issue_comment. Only the second checked whether the
body was a "/command", so "/review" typed on a line was routed to the
conversation path and answered by the model as a question -- paying for
an LLM call and never re-running the review the operator asked for.

Both surfaces now run the same parse. A body starting with "/" whose
first word is not a recognised command stays a comment, so a path or a
fraction still engages the bot normally.
EOF
```

---

### Task 3: GitLab — keep thread context, stop swallowing unknown commands

Two changes: the command event now carries the discussion id and position, and an unrecognised `/foo` falls through to `AuthorReplied` instead of `List.of()` (a silent drop, where the other two providers treat it as a comment).

**Files:**
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabIngress.java` (`note`, ~line 218-243)
- Test: `spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabIngressTest.java`

**Interfaces:**
- Consumes: `ManualCommandReceived(..., ThreadRef, ThreadLocation)` from Task 1; the file's existing `location(JsonNode attrs)` helper.
- Produces: nothing new; behaviour only.

- [ ] **Step 1: Write the failing test**

```java
@Test
void slashCommandInADiffNoteKeepsItsDiscussionAndPosition() {
    String payload = """
            {"object_kind":"note",
             "project":{"path_with_namespace":"acme/widgets"},
             "merge_request":{"iid":7},
             "user":{"id":11,"username":"octocat"},
             "object_attributes":{"noteable_type":"MergeRequest","type":"DiffNote",
               "id":901,"discussion_id":"disc-900","note":"/finding major shadows the field",
               "position":{"new_path":"src/Foo.java","new_line":44}}}""";

    List<IntegrationEvent> events = ingress.translate(headers(), payload);

    assertThat(events).singleElement().isInstanceOfSatisfying(ManualCommandReceived.class, e -> {
        assertThat(e.command()).isEqualTo("finding");
        assertThat(e.args()).isEqualTo("major shadows the field");
        assertThat(e.threadRef()).isEqualTo(new ThreadRef("disc-900"));
        assertThat(e.location()).isEqualTo(new ThreadLocation("src/Foo.java", 44));
    });
}

@Test
void anUnrecognisedSlashWordIsAComment() {
    // Was dropped entirely (List.of()), while Bitbucket and GitHub treated the same text as a
    // comment -- one user action, three outcomes.
    String payload = """
            {"object_kind":"note",
             "project":{"path_with_namespace":"acme/widgets"},
             "merge_request":{"iid":7},
             "user":{"id":11,"username":"octocat"},
             "object_attributes":{"noteable_type":"MergeRequest","type":"DiffNote",
               "id":901,"discussion_id":"disc-900","note":"/usr/lib is the wrong path here",
               "position":{"new_path":"src/Foo.java","new_line":44}}}""";

    List<IntegrationEvent> events = ingress.translate(headers(), payload);

    assertThat(events).singleElement().isInstanceOf(AuthorReplied.class);
}
```

Copy the existing file's header/fixture helpers exactly; `"finding"` must be in the `commands` set the test constructs its ingress with.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-scm-gitlab:test --tests '*GitLabIngressTest*'`
Expected: FAIL — first test: `threadRef()` is null. Second test: no events returned.

- [ ] **Step 3: Restructure `note()` so context is computed first**

Replace the body of `note` after the `noteable_type` guard with:

```java
        String text = attrs.path("note").asText("").trim();
        long iid = payload.path("merge_request").path("iid").asLong();
        RepoRef repo = repo(payload);
        String noteType = attrs.path("type").asText(null);   // DiffNote/DiscussionNote => threaded
        boolean topLevel = noteType == null || noteType.isBlank();
        String discussionId = attrs.path("discussion_id").asText("");
        String noteId = attrs.path("id").asText("");
        ThreadLocation location = location(attrs);

        if (text.startsWith("/")) {
            String[] parts = text.substring(1).split("\\s+", 2);
            String command = parts[0].toLowerCase(Locale.ROOT);
            if (commands.contains(command)) {
                // A threaded note carries its discussion; a top-level note has no thread of its own.
                return List.of(new ManualCommandReceived(repo, iid, command,
                        parts.length > 1 ? parts[1] : "", author(payload.path("user")),
                        topLevel ? null : new ThreadRef(discussionId), location));
            }
            // Not a recognised command: fall through. "/usr/lib" is a path, not an instruction, and
            // dropping it made GitLab the only provider that silently swallowed such a comment.
        }
        return List.of(new AuthorReplied(repo, iid, ReviewIds.reviewId(repo, iid),
                new ThreadRef(discussionId), noteId, text, author(payload.path("user")), topLevel,
                mentions(text), location));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-scm-gitlab:test`
Expected: PASS, all tests in the module.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-gitlab/
git commit -F - <<'EOF'
Keep a GitLab note's thread context on its command

A "/command" note was recognised before its discussion id and position
were read, so a command typed inside a diff thread arrived with no way
to find the thread it was typed in.

An unrecognised "/word" was also returned as no events at all, so a note
opening with a path was silently swallowed on GitLab while Bitbucket and
GitHub treated the same text as an ordinary comment. It now falls
through to the comment path, matching them.
EOF
```

---

### Task 4: Bitbucket — compute thread context before the command branch

**Files:**
- Modify: `spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudIngress.java:144-180`
- Test: `spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudIngressTest.java`

**Interfaces:**
- Consumes: `ManualCommandReceived(..., ThreadRef, ThreadLocation)` from Task 1; the file's existing `location(JsonNode comment)` helper.
- Produces: nothing new; behaviour only.

- [ ] **Step 1: Write the failing test**

```java
@Test
void slashCommandOnAnInlineCommentKeepsItsParentAndInlineAnchor() {
    String payload = """
            {"repository":{"workspace":{"slug":"acme"},"name":"widgets"},
             "pullrequest":{"id":7},
             "comment":{"id":901,"parent":{"id":900},
               "inline":{"path":"src/Foo.java","to":44},
               "content":{"raw":"/finding major shadows the field"},
               "user":{"account_id":"acc-11","nickname":"octocat"}}}""";

    List<IntegrationEvent> events = ingress.translate(headers(), payload);

    assertThat(events).singleElement().isInstanceOfSatisfying(ManualCommandReceived.class, e -> {
        assertThat(e.command()).isEqualTo("finding");
        assertThat(e.threadRef()).isEqualTo(new ThreadRef("900"));
        assertThat(e.location()).isEqualTo(new ThreadLocation("src/Foo.java", 44));
    });
}
```

Match the file's existing fixture helpers and the exact inline-anchor field names its other tests use (`to` vs `from` — read `location(JsonNode)` in the ingress to confirm which it reads).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-scm-bitbucket:test --tests '*BitbucketCloudIngressTest*'`
Expected: FAIL — `threadRef()` is null.

- [ ] **Step 3: Move the context computation above the command branch**

In `comment(JsonNode payload)`, move the `parent`/`hasParent`/`inline`/`topLevel`/`threadRef` block and the `location(comment)` call to sit **before** the `text.startsWith("/")` branch, then pass them:

```java
        JsonNode parent = comment.path("parent").path("id");
        boolean hasParent = !(parent.isMissingNode() || parent.isNull());
        boolean inline = comment.path("inline").isObject();
        boolean topLevel = !hasParent && !inline;
        String threadRef = hasParent ? parent.asText() : comment.path("id").asText();
        ThreadLocation location = location(comment);

        // "/review ..." -> ManualCommandReceived (CONTRACT §10), now carrying the thread it was
        // typed in. A top-level command has no thread of its own.
        if (text.startsWith("/")) {
            String[] parts = text.substring(1).split("\\s+", 2);
            String command = parts[0].toLowerCase(Locale.ROOT);
            if (commands.contains(command)) {
                return List.of(new ManualCommandReceived(repo, prId, command,
                        parts.length > 1 ? parts[1] : "", author,
                        topLevel ? null : new ThreadRef(threadRef), location));
            }
        }

        return List.of(new AuthorReplied(repo, prId, ReviewIds.reviewId(repo, prId),
                new ThreadRef(threadRef), comment.path("id").asText(), text, author, topLevel,
                mentions(text), location));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-scm-bitbucket:test`
Expected: PASS, all tests in the module.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-bitbucket/
git commit -m "Keep a Bitbucket comment's thread context on its command"
```

---

### Task 5: One home for the recognised-command list

**Files:**
- Create: `spire-gateway/src/main/java/dev/codespire/gateway/WebhookCommands.java`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/GitHubWebhookResource.java:31`, `GitLabWebhookResource.java:31`, `BitbucketWebhookResource.java:32`
- Test: `spire-gateway/src/test/java/dev/codespire/gateway/WebhookCommandsTest.java`

**Interfaces:**
- Produces: `WebhookCommands.SUPPORTED` — `Set<String>` containing `"review"` and `"finding"`.

- [ ] **Step 1: Write the failing test**

```java
class WebhookCommandsTest {

    @Test
    void recognisesTheCommandsTheOrchestratorHandles() {
        assertThat(WebhookCommands.SUPPORTED).containsExactlyInAnyOrder("review", "finding");
    }

    @Test
    void isImmutable() {
        // Each ingress does Set.copyOf on construction, but the shared constant is the thing three
        // endpoints hand out — a mutable one would let any of them change the others' behaviour.
        assertThatThrownBy(() -> WebhookCommands.SUPPORTED.add("drop-table"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-gateway:test --tests '*WebhookCommandsTest*'`
Expected: FAIL — cannot find symbol `WebhookCommands`.

- [ ] **Step 3: Create the constants and point the three resources at them**

The **names** go in `spire-contract` and the **gateway's set** composes from them. Both services read
these: the gateway decides what to translate, and `IntegrationSaga` (Task 10) switches on
`e.command()` — and `spire-orchestrator` cannot import from `spire-gateway`. Two string literals in
two services is the duplication this task exists to remove, so put the name where both can see it.

Create `spire-contract/src/main/java/dev/codespire/contract/command/CommentCommands.java`:

```java
package dev.codespire.contract.command;

/**
 * The {@code /command} names carried on {@link dev.codespire.contract.event.IntegrationEvent
 * .ManualCommandReceived}. Part of the wire vocabulary, so both the service that translates a
 * comment into one and the service that acts on it read the same constant.
 *
 * <p>Anything else beginning with "/" is an ordinary comment, not a command.
 */
public final class CommentCommands {

    /** Force a re-review of the pull request's current head. */
    public static final String REVIEW = "review";

    /** File the surrounding thread's issue as a tracked finding. */
    public static final String FINDING = "finding";

    private CommentCommands() {
    }
}
```

Then `spire-gateway/src/main/java/dev/codespire/gateway/WebhookCommands.java`:

```java
package dev.codespire.gateway;

import dev.codespire.contract.command.CommentCommands;

import java.util.Set;

/**
 * The commands the ingresses translate. One set rather than three copies: this was
 * {@code Set.of("review")} written out separately in each webhook resource, so adding a command
 * meant remembering all three, and a provider left behind would silently route the new command to
 * the conversation path instead.
 */
public final class WebhookCommands {

    public static final Set<String> SUPPORTED =
            Set.of(CommentCommands.REVIEW, CommentCommands.FINDING);

    private WebhookCommands() {
    }
}
```

In each of the three resources, replace `private static final Set<String> COMMANDS = Set.of("review");` with `private static final Set<String> COMMANDS = WebhookCommands.SUPPORTED;` and remove the now-unused `java.util.Set` import only if nothing else in the file uses it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-gateway:test`
Expected: PASS.

- [ ] **Step 5: Verify the provider-neutrality check still passes**

Run: `./gradlew :spire-arch:test`
Expected: PASS. `WebhookCommands` names no provider, so it needs no allowlist entry. If the scan fails, do **not** add an allowlist entry — the file has a provider name in it that should not be there.

- [ ] **Step 6: Commit**

```bash
git add spire-gateway/
git commit -m "Give the recognised-command list one home"
```

---

### Task 6: The three ingresses agree, proven by one test

The defect this phase fixes was that the providers *differed*. Only a test that compares them keeps them from differing again.

**Files:**
- Create: `spire-arch/src/test/java/dev/codespire/arch/IngressCommandParityTest.java`
- Test: itself

**Interfaces:**
- Consumes: all three ingress classes and `WebhookCommands.SUPPORTED`.

Check `spire-arch/build.gradle.kts` first: if it does not already have test dependencies on the three `spire-scm-*` modules and `spire-gateway`, add them as `testImplementation`. `spire-arch` is a test-only module, so this creates no production coupling and no ADR-021 violation (all four are Apache-2.0 or test scope).

- [ ] **Step 1: Write the failing test**

```java
/**
 * One user action, one event shape — across every provider.
 *
 * <p>The defect this guards against is not a wrong value in any single ingress; it is that the three
 * DISAGREED. A slash command typed in an inline thread produced four different outcomes across three
 * SCMs, and no per-provider test could see it because each asserted its own behaviour and passed.
 */
class IngressCommandParityTest {

    private record Case(String provider, List<IntegrationEvent> events) {
    }

    /** Each provider's own payload for: "/finding major shadows the field", typed in an inline
     *  thread on src/Foo.java line 44, by octocat, on PR/MR 7 of acme/widgets. */
    private static List<Case> inlineCommandOnEveryProvider() {
        return List.of(
                new Case("github", githubIngress().translate(
                        Map.of("X-GitHub-Event", "pull_request_review_comment"), GITHUB_INLINE_COMMAND)),
                new Case("gitlab", gitlabIngress().translate(
                        Map.of("X-Gitlab-Event", "Note Hook"), GITLAB_INLINE_COMMAND)),
                new Case("bitbucket", bitbucketIngress().translate(
                        Map.of("X-Event-Key", "pullrequest:comment_created"), BITBUCKET_INLINE_COMMAND)));
    }

    @Test
    void everyProviderTurnsAnInlineSlashCommandIntoTheSameCommandEvent() {
        for (Case c : inlineCommandOnEveryProvider()) {
            assertThat(c.events())
                    .as("provider %s", c.provider())
                    .singleElement()
                    .isInstanceOfSatisfying(ManualCommandReceived.class, e -> {
                        assertThat(e.command()).as("%s command", c.provider()).isEqualTo("finding");
                        assertThat(e.args()).as("%s args", c.provider())
                                .isEqualTo("major shadows the field");
                        assertThat(e.prId()).as("%s prId", c.provider()).isEqualTo(7);
                        assertThat(e.location()).as("%s location", c.provider())
                                .isEqualTo(new ThreadLocation("src/Foo.java", 44));
                        assertThat(e.threadRef()).as("%s threadRef", c.provider()).isNotNull();
                    });
        }
    }

    @Test
    void everyProviderTreatsAnUnrecognisedSlashWordAsAComment() {
        for (Case c : unrecognisedSlashOnEveryProvider()) {
            assertThat(c.events()).as("provider %s", c.provider())
                    .singleElement().isInstanceOf(AuthorReplied.class);
        }
    }

    @Test
    void theParityCasesCoverEveryProvider() {
        // Guards the guard: a case list that silently lost a provider would make both tests above
        // pass while covering less. Same shape as spire-arch's own "the scan reached every core
        // module" assertion.
        assertThat(inlineCommandOnEveryProvider()).extracting(Case::provider)
                .containsExactlyInAnyOrder("github", "gitlab", "bitbucket");
    }
}
```

Write `unrecognisedSlashOnEveryProvider()` as the same three-case list using `"/usr/lib is the wrong path here"` as the body. Build each ingress with `WebhookCommands.SUPPORTED` as its command set and a fixed test secret. The three payload constants are the same JSON bodies used in Tasks 2–4; copy them rather than referencing the other modules' test classes.

**`threadRef` is asserted non-null, not equal to a value, on purpose**: the ref is opaque and each provider's is genuinely different (a comment id, a discussion id). The parity property is *that it is carried*, not that it matches across providers — asserting equality would be asserting something false.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-arch:test --tests '*IngressCommandParityTest*'`
Expected: FAIL initially only if a prior task regressed; if Tasks 2–4 are complete this should pass. **Verify it discriminates**: temporarily revert the `reviewCommentReply` command check from Task 2, confirm the GitHub case fails with a clear `provider github` message, then restore it.

- [ ] **Step 3: Run the full fast suite**

Run: `./gradlew testFast`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add spire-arch/
git commit -F - <<'EOF'
Assert the three ingresses agree on slash commands

Each provider had its own ingress test and each passed; what none could
see was that they disagreed with each other. This runs one user action
through all three and asserts one event shape, plus a coverage check so
a case list that quietly lost a provider fails rather than passing with
less.
EOF
```

---

# Phase 2 — Conversation-derived findings

### Task 7: The contract — command, event, and aggregate rule

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/RecordCommand.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/DomainEvent.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/lifecycle/ReviewState.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/lifecycle/ReviewLifecycle.java:51-70,96-115`
- Test: `spire-contract/src/test/java/dev/codespire/contract/lifecycle/ReviewLifecycleTest.java`

**Interfaces:**
- Produces:
  - `RecordCommand.RaiseConversationFinding(ThreadRef threadRef, String path, int line, Severity severity, String message, String triggeringCommentId)`
  - `DomainEvent.ConversationFindingRaised(ThreadRef threadRef, String path, int line, Severity severity, String triggeringCommentId)` — **no message**; it may quote source, so it goes to the encrypted read model, not the event log.
  - `ReviewState.raisedFindingComments()` → `Set<String>`
  - `ReviewState.initial()` unchanged in arity from a caller's view — it gains `Set.of()` for the new component.

- [ ] **Step 1: Write the failing test**

```java
@Test
void raisingAConversationFindingAppendsIt() {
    ReviewState state = reviewing("c1");

    List<DomainEvent> events = lifecycle.decide(new RecordCommand.RaiseConversationFinding(
            new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR,
            "shadows the field", "c-901"), state);

    assertThat(events).singleElement().isEqualTo(new DomainEvent.ConversationFindingRaised(
            new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, "c-901"));
}

@Test
void raisingTheSameCommentTwiceAppendsNothingTheSecondTime() {
    // ManualCommandReceived is at-least-once over Kafka. The worker's claim guards the SCM post;
    // only the aggregate can stop a redelivery appending a second finding.
    ReviewState state = reviewing("c1");
    RecordCommand.RaiseConversationFinding cmd = new RecordCommand.RaiseConversationFinding(
            new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, "shadows the field", "c-901");

    ReviewState after = lifecycle.evolve(state, lifecycle.decide(cmd, state).getFirst());

    assertThat(lifecycle.decide(cmd, after)).isEmpty();
}

@Test
void aDifferentCommentOnTheSameThreadStillAppends() {
    // The key is the triggering comment, not the thread: a second /finding in one discussion is a
    // second finding, and keying on the thread would silently drop it.
    ReviewState state = reviewing("c1");
    RecordCommand.RaiseConversationFinding first = new RecordCommand.RaiseConversationFinding(
            new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, "shadows the field", "c-901");
    ReviewState after = lifecycle.evolve(state, lifecycle.decide(first, state).getFirst());

    RecordCommand.RaiseConversationFinding second = new RecordCommand.RaiseConversationFinding(
            new ThreadRef("t-900"), "src/Foo.java", 51, Severity.MAJOR, "and this leaks", "c-902");

    assertThat(lifecycle.decide(second, after)).hasSize(1);
}

@Test
void aConversationFindingDoesNotDisturbTheReviewsOwnFindingCount() {
    // ReviewOutcomeRecorded answers "how many findings did the review of this commit produce".
    // A conversation finding did not come from that call and must not rewrite that number.
    ReviewState state = reviewing("c1");
    ReviewState after = lifecycle.evolve(state, new DomainEvent.ConversationFindingRaised(
            new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, "c-901"));

    assertThat(after.status()).isEqualTo(ReviewState.Status.REVIEWING);
    assertThat(after.currentCommit()).isEqualTo("c1");
}
```

Reuse the file's existing `reviewing(String commit)` helper (or its equivalent — read the file and match).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-contract:test --tests '*ReviewLifecycleTest*'`
Expected: FAIL — cannot find symbol `RaiseConversationFinding`.

- [ ] **Step 3: Add the command, the event, and the state field**

`RecordCommand.java`:

```java
    /**
     * A human ran {@code /finding} in a review thread. The message rides here on its way to the
     * encrypted read model; it does NOT reach the domain event, which keeps only the anchor and
     * severity (a finding message may quote source code — DATA-MODEL §5).
     */
    record RaiseConversationFinding(ThreadRef threadRef, String path, int line, Severity severity,
                                    String message, String triggeringCommentId) implements RecordCommand {
    }
```

`DomainEvent.java`:

```java
    /**
     * A finding raised from a conversation rather than from reviewing the diff. Anchor and severity
     * only — replay-safe, no source quoted. The message lives in the encrypted read model, as every
     * other finding's does.
     */
    record ConversationFindingRaised(ThreadRef threadRef, String path, int line, Severity severity,
                                     String triggeringCommentId) implements DomainEvent {
    }
```

`ReviewState.java` — add the component, the `initial()` value, and extend the javadoc:

```java
public record ReviewState(String reviewId,
                          RepoRef repo,
                          long prId,
                          Status status,
                          String currentCommit,
                          Set<String> reviewedCommits,
                          String summaryCommentId,
                          Map<String, ThreadState> threads,
                          Set<String> raisedFindingComments) {
```

```java
    public static ReviewState initial() {
        return new ReviewState(null, null, 0, Status.IDLE, null, Set.of(), null, Map.of(), Set.of());
    }
```

`ReviewLifecycle.java` — add to `decide`:

```java
            case RaiseConversationFinding c -> state.raisedFindingComments().contains(c.triggeringCommentId())
                    ? List.of()   // redelivered webhook — idempotent no-op, like an already-reviewed commit
                    : List.of(new ConversationFindingRaised(c.threadRef(), c.path(), c.line(),
                            c.severity(), c.triggeringCommentId()));
```

and to `evolve`:

```java
            case ConversationFindingRaised e -> withRaisedFinding(state, e.triggeringCommentId());
```

with the helper beside the existing `withThread`:

```java
    private static ReviewState withRaisedFinding(ReviewState state, String commentId) {
        Set<String> raised = new java.util.HashSet<>(state.raisedFindingComments());
        raised.add(commentId);
        return new ReviewState(state.reviewId(), state.repo(), state.prId(), state.status(),
                state.currentCommit(), state.reviewedCommits(), state.summaryCommentId(),
                state.threads(), Set.copyOf(raised));
    }
```

Every other construction site of `ReviewState` in `ReviewLifecycle` (the `with` and `withThread` helpers) must pass `state.raisedFindingComments()` through — the compiler will point at each.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-contract:test`
Expected: PASS, including the snapshot gate. Regenerate the golden file if `ConversationFindingRaised` is reported as new — it is additive.

- [ ] **Step 5: Commit**

```bash
git add spire-contract/
git commit -F - <<'EOF'
Add the aggregate path for a conversation-raised finding

The aggregate is the only writer of domain events (ADR-010), so a
finding raised in a thread needs its own command and event rather than
a direct read-model write.

The event carries the anchor and severity only. A finding's message may
quote source code, so it goes to the encrypted read model like every
other finding's, and never into the replayable event log.

ReviewState gains an idempotency set keyed on the triggering comment.
The command event arrives at least once, and the worker's claim guards
the posted reply rather than the append -- without a key held by the
single writer, a redelivery files the finding a second time. Keyed on
the comment and not the thread, so a second /finding in one discussion
is a second finding.
EOF
```

---

### Task 8: The read model stores a finding's origin

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewDetail.java:64`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` (`toView` ~1884, plus a new `addConversationFinding`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewProjectionConversationFindingTest.java`

**Interfaces:**
- Consumes: `FindingView(String sev, String loc, String msg, String threadRef)` as it exists today.
- Produces:
  - `FindingView(String sev, String loc, String msg, String threadRef, String origin)` — `origin` is `null` (review-derived, and what every stored row already deserializes to) or `"conversation"`.
  - `ReviewProjection.addConversationFinding(String reviewId, String threadRef, String path, int line, Severity severity, String message)` → `void`.

- [ ] **Step 1: Write the failing test**

```java
@QuarkusTest
class ReviewProjectionConversationFindingTest {

    @Inject
    ReviewProjection projection;

    @Test
    void aConversationFindingJoinsTheCarryForwardBaseline() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        PriorRun prior = projection.priorRunFor(reviewId).orElseThrow();
        assertThat(prior.findings()).extracting(PriorFinding::path, PriorFinding::line)
                .contains(tuple("src/Foo.java", 44));
    }

    @Test
    void aConversationFindingOnAnAlreadyFlaggedLineMergesRatherThanDoubling() {
        // dedupeByAnchor already enforces one anchor = one tracked concern. Nothing in the new code
        // fails if that stops working, which is exactly why it is asserted here.
        String reviewId = registerReviewWithOpenFindings("src/Foo.java:44", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "and it also shadows the field");

        List<ReviewDetail.FindingView> open = projection.openFindingsFor(reviewId);
        assertThat(open).filteredOn(f -> "src/Foo.java:44".equals(f.loc())).hasSize(1);
        assertThat(open.getFirst().msg()).contains("also shadows the field");
    }

    @Test
    void aStoredRowWrittenBeforeOriginExistedReadsBackAsReviewDerived() {
        // open_findings_json rows already in the database have no origin field. Jackson leaves it
        // null, which must mean "the review reported this", not an unreadable row.
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        List<ReviewDetail.FindingView> open = projection.openFindingsFor(reviewId);

        assertThat(open).isNotEmpty();
        assertThat(open.getFirst().origin()).isNull();
    }

    @Test
    void aConversationFindingIsMarkedAsOne() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        assertThat(projection.openFindingsFor(reviewId))
                .filteredOn(f -> "src/Foo.java:44".equals(f.loc()))
                .singleElement()
                .extracting(ReviewDetail.FindingView::origin)
                .isEqualTo("conversation");
    }
}
```

`registerReviewWithOpenFindings` is a local helper: register a review row via the projection's existing registration method, then write one open finding through `recordOpenFindings`. Read a neighbouring projection test (`ReviewProjection*Test`) and reuse its setup shape and Testcontainers wiring exactly.

If `openFindingsFor` does not exist as a public read, add it in Step 3 as a thin wrapper over the existing `parseFindings(open_findings_json)` path rather than duplicating the decrypt.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*ReviewProjectionConversationFindingTest*'`
Expected: FAIL — cannot find symbol `addConversationFinding`.

- [ ] **Step 3: Add the origin field and the merge**

`ReviewDetail.java`:

```java
    /**
     * One finding as the dashboard renders it. {@code origin} is null for a finding the review
     * produced from the diff — which is every row written before conversation findings existed — and
     * {@code "conversation"} for one a human filed with {@code /finding}. Provenance differs, and a
     * reader should not have to guess which they are looking at.
     */
    public record FindingView(String sev, String loc, String msg, String threadRef, String origin) {

        /** A review-derived finding: the common case, and what a stored row without the field is. */
        public FindingView(String sev, String loc, String msg, String threadRef) {
            this(sev, loc, msg, threadRef, null);
        }
    }
```

The compiler will point at every construction site; the 4-arg constructor keeps them all compiling unchanged. `toView(Finding)` stays 4-arg.

`ReviewProjection.java` — add beside `recordOpenFindings`:

```java
    /**
     * Add a finding raised in a conversation to the carry-forward baseline.
     *
     * <p>Deliberately {@code open_findings_json} and NOT {@code findings_json}: the latter is what
     * the review of a commit produced — a truthful record of one model call, copied to
     * {@code posted_findings_json} as the run snapshot by {@link #recordPosted}. A conversation
     * finding did not come from that call. {@code open_findings_json} is already defined as the
     * carry-forward baseline, which is exactly what this is: something now open that the next round
     * must reconcile against and exclude from re-reporting.
     *
     * <p>Runs the same {@link #dedupeByAnchor} the baseline is always written through, so a
     * {@code /finding} on a line that already has an open finding merges into it rather than
     * doubling the count.
     */
    @Transactional
    public void addConversationFinding(String reviewId, String threadRef, String path, int line,
                                       Severity severity, String message) {
        List<ReviewDetail.FindingView> open = new ArrayList<>(openFindingsFor(reviewId));
        open.add(new ReviewDetail.FindingView(severitySlug(severity), path + ":" + line, message,
                threadRef, "conversation"));
        List<ReviewDetail.FindingView> deduped = dedupeByAnchor(open);
        String json;
        try {
            json = mapper.writeValueAsString(deduped);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize open findings after a conversation finding", e);
            return;
        }
        String encrypted = encryption.encryptString(json, reviewId);
        update("UPDATE review_status SET open_findings_json = ?, updated_at = now() WHERE review_id = ?",
                ps -> {
                    ps.setString(1, encrypted);
                    ps.setString(2, reviewId);
                });
    }

    /** The review's current carry-forward baseline, decrypted. */
    public List<ReviewDetail.FindingView> openFindingsFor(String reviewId) {
        return parseFindings(readColumn(reviewId, "open_findings_json"), reviewId);
    }
```

If no `readColumn(reviewId, column)` helper exists, write `openFindingsFor` as a direct `SELECT open_findings_json FROM review_status WHERE review_id = ?` in the file's existing query style, returning `List.of()` on a `SQLException` with a `LOG.warnf` — matching how `priorRunFor` degrades.

Check `mergeFindingGroup`: it keeps the **first** entry's severity. A conversation finding appended last therefore keeps the existing finding's severity when they share an anchor, which is correct — the merged concern is not downgraded by a later MINOR.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests '*ReviewProjectionConversationFindingTest*'`
Expected: PASS, all four tests.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -F - <<'EOF'
Store a conversation finding on the carry-forward baseline

open_findings_json, not findings_json: the latter records what the
review of a commit produced and is copied to posted_findings_json as
the run snapshot, so writing a conversation finding there would corrupt
a record of what the model said. The baseline is already defined as
"open now and reconciled next round", which is what this is -- so
reconciliation and the re-report exclusion list need no new plumbing.

Findings gain an origin so the dashboard can tell a defect the review
reported from one a human filed. Null means review-derived, which is
what every stored row deserializes to.
EOF
```

---

### Task 9: Parse `/finding`, resolve its anchor, refuse honestly

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ConversationFindings.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ConversationFindingsTest.java`

**Interfaces:**
- Produces:
  - `ConversationFindings.parse(String args)` → `ParsedFinding(Severity severity, String message)`; severity defaults to `Severity.MINOR`.
  - `ConversationFindings.Outcome` — a sealed interface with `Filed(ThreadRef threadRef, String path, int line, Severity severity, String message)` and `Refused(String replyText)`.
  - `ConversationFindings.resolve(ManualCommandReceived event, ThreadLocation storedLocation)` → `Outcome`.

Pure logic, no injection — so it unit-tests without Testcontainers and lands in `testFast`.

- [ ] **Step 1: Write the failing test**

```java
class ConversationFindingsTest {

    @Test
    void bareFindingIsMinor() {
        assertThat(ConversationFindings.parse("")).isEqualTo(
                new ConversationFindings.ParsedFinding(Severity.MINOR, ""));
    }

    @Test
    void aLeadingSeverityWordSetsTheSeverity() {
        assertThat(ConversationFindings.parse("major shadows the field")).isEqualTo(
                new ConversationFindings.ParsedFinding(Severity.MAJOR, "shadows the field"));
    }

    @Test
    void severityIsCaseInsensitive() {
        assertThat(ConversationFindings.parse("BLOCKER drops the lock")).isEqualTo(
                new ConversationFindings.ParsedFinding(Severity.BLOCKER, "drops the lock"));
    }

    @Test
    void aFirstWordThatIsNotASeverityIsPartOfTheMessage() {
        // "/finding this shadows the field" must file a MINOR with that note, not refuse on a typo.
        assertThat(ConversationFindings.parse("this shadows the field")).isEqualTo(
                new ConversationFindings.ParsedFinding(Severity.MINOR, "this shadows the field"));
    }

    @Test
    void theEventsOwnLocationWins() {
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(
                command("major shadows the field", new ThreadRef("t-900"),
                        new ThreadLocation("src/Foo.java", 44)),
                new ThreadLocation("src/Stale.java", 9));

        assertThat(outcome).isEqualTo(new ConversationFindings.Filed(
                new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MAJOR, "shadows the field"));
    }

    @Test
    void theStoredThreadLocationIsTheFallback() {
        // Not every provider reports a location on every comment; review_thread has recorded where
        // a human-started inline thread sits since V17/V27.
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(
                command("", new ThreadRef("t-900"), null),
                new ThreadLocation("src/Foo.java", 44));

        assertThat(outcome).isEqualTo(new ConversationFindings.Filed(
                new ThreadRef("t-900"), "src/Foo.java", 44, Severity.MINOR, ""));
    }

    @Test
    void withNoAnchorAtAllItRefusesWithAnExplanation() {
        // A summary or top-level comment. Refusing SILENTLY is the failure this project has already
        // shipped twice -- the turn cap posted nothing when reached, and a dead tunnel looked
        // identical. A command that does nothing must say so.
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(
                command("major something", null, null), null);

        assertThat(outcome).isInstanceOfSatisfying(ConversationFindings.Refused.class, r ->
                assertThat(r.replyText()).contains("needs to be on a specific line"));
    }

    private static ManualCommandReceived command(String args, ThreadRef thread, ThreadLocation loc) {
        return new ManualCommandReceived(new RepoRef("acme", "widgets"), 7, "finding", args,
                new Author("u-1", "octocat"), thread, loc);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*ConversationFindingsTest*'`
Expected: FAIL — cannot find symbol `ConversationFindings`.

- [ ] **Step 3: Implement**

```java
package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;

import java.util.Locale;

/**
 * Turns a {@code /finding} command into either a finding to file or a refusal to say out loud.
 *
 * <p>Pure: no injection, no I/O. The stored thread location is passed in by the caller, which is the
 * only part that needs a database.
 */
public final class ConversationFindings {

    /** Said to an authorized author who used the command in a place it cannot work. Deliberately
     *  different from an authorization refusal, which stays silent so a prober learns nothing. */
    static final String NO_ANCHOR_REPLY =
            "`/finding` needs to be on a specific line. Open an inline comment on the line in "
            + "question and run it there.";

    private ConversationFindings() {
    }

    public record ParsedFinding(Severity severity, String message) {
    }

    public sealed interface Outcome {
    }

    public record Filed(ThreadRef threadRef, String path, int line, Severity severity, String message)
            implements Outcome {
    }

    public record Refused(String replyText) implements Outcome {
    }

    /**
     * {@code "major shadows the field"} → MAJOR + the rest. A first word that is not a severity is
     * the start of the message, not an error: refusing on a typo would cost a round trip in the
     * thread for something the default handles.
     */
    public static ParsedFinding parse(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            return new ParsedFinding(Severity.MINOR, "");
        }
        String[] parts = trimmed.split("\\s+", 2);
        Severity severity = severityOrNull(parts[0]);
        return severity == null
                ? new ParsedFinding(Severity.MINOR, trimmed)
                : new ParsedFinding(severity, parts.length > 1 ? parts[1] : "");
    }

    private static Severity severityOrNull(String word) {
        try {
            return Severity.valueOf(word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notASeverity) {
            return null;
        }
    }

    /**
     * @param storedLocation where {@code review_thread} records this thread sitting, or null. Used
     * only when the event carries no location of its own — not every provider reports one on every
     * comment surface.
     */
    public static Outcome resolve(ManualCommandReceived event, ThreadLocation storedLocation) {
        ThreadLocation anchor = event.location() != null ? event.location() : storedLocation;
        if (event.threadRef() == null || anchor == null) {
            return new Refused(NO_ANCHOR_REPLY);
        }
        ParsedFinding parsed = parse(event.args());
        return new Filed(event.threadRef(), anchor.path(), anchor.line(),
                parsed.severity(), parsed.message());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests '*ConversationFindingsTest*'`
Expected: PASS, all seven tests.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -m "Parse and anchor a /finding command"
```

---

### Task 10: Wire `/finding` into the saga

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java:275-279` (the command switch)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/DomainEventSink.java:43-57`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ConversationFindingSagaTest.java`

**Interfaces:**
- Consumes: `ConversationFindings.resolve/parse` (Task 9), `RecordCommand.RaiseConversationFinding` (Task 7), `ReviewProjection.addConversationFinding` (Task 8), `ActionCommand.ConfirmFinding` (Task 11 — declare it in this task's Step 3 so the saga compiles; Task 11 adds the worker handler).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```java
@QuarkusTest
class ConversationFindingSagaTest {

    @Test
    void findingCommandFilesTheFindingAndConfirmsInThread() {
        String reviewId = registerCompletedReview();

        saga.on(new ManualCommandReceived(REPO, PR_ID, "finding", "major shadows the field",
                ALLOWED_AUTHOR, new ThreadRef("t-900"), new ThreadLocation("src/Foo.java", 44)));

        assertThat(projection.openFindingsFor(reviewId))
                .extracting(ReviewDetail.FindingView::loc).contains("src/Foo.java:44");
        assertThat(emittedCommands()).filteredOn(ActionCommand.ConfirmFinding.class::isInstance)
                .singleElement().satisfies(c -> {
                    ActionCommand.ConfirmFinding confirm = (ActionCommand.ConfirmFinding) c;
                    assertThat(confirm.severity()).isEqualTo(Severity.MAJOR);
                    assertThat(confirm.path()).isEqualTo("src/Foo.java");
                });
    }

    @Test
    void findingOnASummaryCommentRepliesInsteadOfFilingNothingSilently() {
        String reviewId = registerCompletedReview();

        saga.on(new ManualCommandReceived(REPO, PR_ID, "finding", "major something",
                ALLOWED_AUTHOR, null, null));

        assertThat(projection.openFindingsFor(reviewId)).isEmpty();
        assertThat(emittedCommands()).filteredOn(ActionCommand.ConfirmFinding.class::isInstance)
                .isEmpty();
        assertThat(emittedCommands()).filteredOn(ActionCommand.ReplyInThread.class::isInstance)
                .isEmpty();   // no thread to reply into either -- see below
        assertThat(timeline.entriesFor(reviewId))
                .anySatisfy(entry -> assertThat(entry.detail()).contains("needs to be on a specific line"));
    }

    @Test
    void anAuthorOutsideTheAllowlistFilesNothingAndIsNotRepliedTo() {
        // The gate sits ahead of the command switch precisely so a new command inherits it. A reply
        // would confirm to a prober that the command is wired and cost an outbound comment per probe.
        String reviewId = registerCompletedReview();

        saga.on(new ManualCommandReceived(REPO, PR_ID, "finding", "blocker anything",
                STRANGER, new ThreadRef("t-900"), new ThreadLocation("src/Foo.java", 44)));

        assertThat(projection.openFindingsFor(reviewId)).isEmpty();
        assertThat(emittedCommands()).isEmpty();
    }

    @Test
    void findingOnAnArchivedReviewIsRefusedByTheExistingGate() {
        String reviewId = registerCompletedReview();
        projection.archiveReview(REPO.workspace(), REPO.slug(), PR_ID);

        saga.on(new ManualCommandReceived(REPO, PR_ID, "finding", "major shadows the field",
                ALLOWED_AUTHOR, new ThreadRef("t-900"), new ThreadLocation("src/Foo.java", 44)));

        assertThat(projection.openFindingsFor(reviewId)).isEmpty();
    }

    @Test
    void aRedeliveredFindingCommandFilesOnlyOnce() {
        String reviewId = registerCompletedReview();
        ManualCommandReceived event = new ManualCommandReceived(REPO, PR_ID, "finding",
                "major shadows the field", ALLOWED_AUTHOR,
                new ThreadRef("t-900"), new ThreadLocation("src/Foo.java", 44));

        saga.on(event);
        saga.on(event);

        assertThat(projection.openFindingsFor(reviewId))
                .filteredOn(f -> "src/Foo.java:44".equals(f.loc())).hasSize(1);
    }
}
```

For the refusal test: the refusal has no thread to reply into when `threadRef` is null, so it is timeline-only. Read `IntegrationSaga`'s existing timeline helper and assert through whatever the sibling saga tests use (`ConversationSagaTest` is the closest model — copy its harness, fixtures and assertion style).

The redelivery test exercises Task 7's aggregate key end to end. If `saga.on` is not the actual entry-point name, read the class and use the real one.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*ConversationFindingSagaTest*'`
Expected: FAIL — `/finding` reaches the `else` branch and logs "no handler"; nothing is filed.

- [ ] **Step 3: Add the branch**

Declare the command in `ActionCommand.java`:

```java
    /**
     * Confirm in-thread that a {@code /finding} was filed. Fixed text, so it carries no LLM
     * credential — filing a finding costs no tokens, exactly as the turn-cap and archived notices do.
     */
    record ConfirmFinding(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                          Severity severity, String path, int line, String scmCredential)
            implements ActionCommand {
    }
```

In `IntegrationSaga.onManualCommand`, extend the switch:

```java
        switch (e.command()) {
            case CommentCommands.REVIEW -> triggerManualReview(e);
            case CommentCommands.FINDING -> raiseConversationFinding(reviewId, e);
            default -> LOG.infof("Manual /%s command received — no handler", e.command());
        }
```

`CommentCommands` is the `spire-contract` class from Task 5 — `spire-orchestrator` cannot import from
`spire-gateway`, which is why the names live in the contract and the gateway's set composes from
them. Both constants are compile-time `String` constants, so they are legal `case` labels.

Run `./gradlew :spire-arch:test` afterwards: command names are not provider names, so no allowlist
entry is needed. If the scan fails, something else in the diff named a provider.

```java
    /**
     * A human filed a finding from a discussion. No LLM call and no spend gate — the model is not
     * asked anything; a person already decided.
     */
    private void raiseConversationFinding(String reviewId, ManualCommandReceived e) {
        ThreadLocation stored = e.threadRef() == null
                ? null : threads.locationOf(reviewId, threads.rootOf(reviewId, e.threadRef()));
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(e, stored);
        switch (outcome) {
            case ConversationFindings.Refused r -> {
                timeline.record("integration", "refused:RaiseConversationFinding", reviewId, r.replyText());
                LOG.infof("Refused /finding on %s — no line anchor (thread=%s)",
                        reviewId, e.threadRef() == null ? "none" : e.threadRef().value());
            }
            case ConversationFindings.Filed f -> {
                ThreadRef root = threads.rootOf(reviewId, f.threadRef());
                List<DomainEvent> appended = lifecycle.handle(reviewId,
                        new RecordCommand.RaiseConversationFinding(root, f.path(), f.line(),
                                f.severity(), f.message(), e.commentId()));
                if (appended.isEmpty()) {
                    LOG.infof("Ignoring redelivered /finding on %s (comment already raised one)", reviewId);
                    return;
                }
                projection.addConversationFinding(reviewId, root.value(), f.path(), f.line(),
                        f.severity(), f.message());
                reviewProviders.resolveForReview(reviewId).ifPresent(provider ->
                        commands.emit(new ActionCommand.ConfirmFinding(reviewId, e.repo(), e.prId(),
                                root, f.severity(), f.path(), f.line(),
                                workerCredentials.pack(provider))));
            }
        }
    }
```

`ManualCommandReceived` has no `commentId` component. **Add one** in this step — it is the idempotency key Task 7's aggregate rule needs, and without it a redelivery cannot be recognised. Extend the record to `(repo, prId, command, args, author, threadRef, location, commentId)`, keep the 5-arg constructor delegating with three nulls, add a `commentId` to each ingress's command emission (each already reads a comment id for `AuthorReplied`), and extend Task 1's wire test to cover it.

Add `locationOf(String reviewId, ThreadRef thread)` to `ReviewThreadView`, returning `ThreadLocation.of(path, line)` from the row or null — the class already reads that table and `markThreadLocation` writes those columns.

In `DomainEventSink.project`, add:

```java
            case DomainEvent.ConversationFindingRaised e ->
                    projection.appendEvent(reviewId, "domain", "ConversationFindingRaised",
                            e.severity() + " at " + e.path() + ":" + e.line(), e.threadRef().value());
```

and to the description switch:

```java
            case DomainEvent.ConversationFindingRaised e ->
                    e.severity() + " at " + e.path() + ":" + e.line();
```

The event is projected as a **timeline entry only**. The finding itself was written by `addConversationFinding` in the saga, which is the same shape `ResultSaga` uses for findings today; do not write it twice.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests '*ConversationFindingSagaTest*'`
Expected: PASS, all five tests.

- [ ] **Step 5: Run the whole orchestrator suite**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS. The `ManualCommandReceived` arity change will break existing construction sites in tests — fix each by using the 5-arg constructor.

- [ ] **Step 6: Commit**

```bash
git add spire-contract/ spire-orchestrator/ spire-scm-github/ spire-scm-gitlab/ spire-scm-bitbucket/
git commit -F - <<'EOF'
File a finding when a human runs /finding in a thread

The command reaches the aggregate, which appends the finding and keys
it on the triggering comment so a redelivery is a no-op. The message
goes to the encrypted read model and joins the carry-forward baseline,
so the next round reconciles it and excludes it from re-reporting.

A /finding with no line to anchor to is refused with an explanation
rather than silently doing nothing -- the failure this project has
shipped twice, where a bot that goes quiet is indistinguishable from a
lost webhook. An author outside the allowlist is still met with
silence: that refusal is an authorization decision, and a reply would
confirm to a prober that the command is wired.

The command event gains the triggering comment id, which is what makes
the aggregate's idempotency key available.
EOF
```

---

### Task 11: The worker confirms in-thread

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/FollowUpWorker.java`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/CommandDispatcher.java:61-62`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/FollowUpWorkerConfirmFindingTest.java`

**Interfaces:**
- Consumes: `ActionCommand.ConfirmFinding` (Task 10).
- Produces: `FollowUpWorker.confirmFinding(ActionCommand.ConfirmFinding)` → `void`; `FollowUpWorker.confirmText(Severity, String path, int line)` → `String` (package-private, for direct assertion).

- [ ] **Step 1: Write the failing test**

```java
class FollowUpWorkerConfirmFindingTest {

    @Test
    void namesTheSeverityAndTheAnchor() {
        String text = FollowUpWorker.confirmText(Severity.MAJOR, "src/Foo.java", 44);

        assertThat(text).contains("MAJOR").contains("src/Foo.java:44");
    }

    @Test
    void postsOncePerTriggeringComment() {
        // Same claim shape as followup: a redelivered ConfirmFinding must not post a second reply.
        RecordingSink sink = new RecordingSink();
        FakeIdempotency claims = new FakeIdempotency();

        worker(sink, claims).confirmFinding(command("c-901"));
        worker(sink, claims).confirmFinding(command("c-901"));

        assertThat(sink.replies()).hasSize(1);
    }

    @Test
    void doesNotConsumeAConversationTurn() {
        // Confirming costs no LLM call and must not push the thread toward its turn cap: the notice
        // is not the bot taking part in the discussion. TurnCapNotified exists for the same reason.
        RecordingResults results = new RecordingResults();

        worker(new RecordingSink(), new FakeIdempotency(), results).confirmFinding(command("c-901"));

        assertThat(results.emitted()).noneMatch(FollowUpPosted.class::isInstance);
    }
}
```

Read `FollowUpWorkerPromptTest` and the existing worker tests for the exact fake/stub classes this module already has (`RecordingSink`, the idempotency fake, the results emitter fake) and reuse them rather than writing new ones. If they do not exist under those names, use whatever the module's tests actually use.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-review-worker:test --tests '*FollowUpWorkerConfirmFindingTest*'`
Expected: FAIL — cannot find symbol `confirmText`.

- [ ] **Step 3: Implement**

Add to `FollowUpWorker`, beside `notifyTurnCap` and `notifyArchived`:

```java
    /**
     * Confirm in-thread that a {@code /finding} was filed.
     *
     * <p>Fixed text and no LLM call, like the turn-cap and archived notices. The claim is per
     * TRIGGERING COMMENT, not per thread: a second {@code /finding} in one discussion is a second
     * finding and deserves its own confirmation, unlike the turn-cap notice which is once per thread
     * by design.
     *
     * <p>Emits no {@code FollowUpPosted}: that event bumps the thread's turn count, and confirming a
     * filing is not the bot taking a turn in the conversation.
     */
    public void confirmFinding(ActionCommand.ConfirmFinding command) {
        WorkerScmClients.Clients clients = scm.forCommand(command);
        String key = "finding:" + command.triggeringCommentId();
        if (idempotency.claim(command.reviewId(), command.threadRef().value(), key)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            LOG.infof("Finding confirmation already posted for %s thread %s — staying quiet",
                    command.reviewId(), command.threadRef().value());
            return;
        }
        CommentRef ref = clients.comments().replyInThread(command.repo(), command.prId(),
                command.threadRef(),
                confirmText(command.severity(), command.path(), command.line()));
        idempotency.markPosted(command.reviewId(), command.threadRef().value(), key, ref.commentId());
        LOG.infof("Confirmed conversation finding on %s at %s:%d (%s)",
                command.reviewId(), command.path(), command.line(), command.severity());
    }

    static String confirmText(Severity severity, String path, int line) {
        return "Filed as **" + severity + "** at `" + path + ":" + line + "`. It will be tracked "
                + "with the review's other findings and reconciled on the next push.";
    }
```

`ConfirmFinding` needs a `triggeringCommentId` component for this claim key — add it to the record in `ActionCommand.java` and pass `e.commentId()` from the saga in Task 10.

In `CommandDispatcher.on`:

```java
                case ConfirmFinding c -> followUpWorker.confirmFinding(c);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-review-worker:test`
Expected: PASS, all tests in the module.

- [ ] **Step 5: Commit**

```bash
git add spire-contract/ spire-orchestrator/ spire-review-worker/
git commit -F - <<'EOF'
Confirm a filed finding in the thread it came from

Fixed text and no LLM credential, like the turn-cap and archived
notices -- filing a finding costs no tokens. Claimed per triggering
comment rather than per thread, because a second /finding in one
discussion is a second finding and earns its own confirmation.

Emits no FollowUpPosted: that event bumps the thread's turn count, and
confirming a filing is not the bot taking a turn in the conversation.
EOF
```

---

### Task 12: The dashboard shows where a finding came from

**Files:**
- Modify: `spire-ui/src/api.ts:57-62`
- Modify: `spire-ui/src/render.tsx`
- Test: `spire-ui/src/render.finding-origin.test.tsx`

**Interfaces:**
- Consumes: `FindingView.origin` from Task 8, arriving as `origin?: 'conversation'` in JSON.
- Produces: nothing further.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { findingsCard } from './render';

describe('finding origin', () => {
  it('marks a finding a human filed from a discussion', () => {
    render(findingsCard([
      { sev: 'suggestion', loc: 'src/Foo.java:44', msg: 'shadows the field', origin: 'conversation' },
    ]));

    expect(screen.getByText(/from discussion/i)).toBeInTheDocument();
  });

  it('leaves a review-derived finding unmarked', () => {
    render(findingsCard([{ sev: 'warning', loc: 'src/Bar.java:10', msg: 'leaks a handle' }]));

    expect(screen.queryByText(/from discussion/i)).not.toBeInTheDocument();
  });

  it('treats a stored row with no origin as review-derived', () => {
    // Every row written before this field existed deserializes with origin undefined. Rendering
    // those as "from discussion" would attribute the model's findings to people.
    render(findingsCard([
      { sev: 'warning', loc: 'src/Bar.java:10', msg: 'leaks a handle', origin: undefined },
    ]));

    expect(screen.queryByText(/from discussion/i)).not.toBeInTheDocument();
  });
});
```

Read `render.findings-unified.test.tsx` first and match how it imports and invokes the findings renderer — `findingsCard` above is a placeholder for whatever that file actually calls. Use the real export.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/render.finding-origin.test.tsx`
Expected: FAIL — "from discussion" not found.

- [ ] **Step 3: Add the field and the badge**

`api.ts`:

```ts
export interface Finding {
  sev: 'critical' | 'warning' | 'suggestion' | 'nit';
  loc: string;
  msg: string;
  threadRef?: string; // the SCM thread this finding owns (present when it has a conversation)
  // Absent for a finding the review produced from the diff — which is every row stored before
  // conversation findings existed. 'conversation' means a human filed it with /finding.
  origin?: 'conversation';
}
```

In the findings renderer, beside the severity pill:

```tsx
{f.origin === 'conversation' && (
  <span className="pill pill--muted" title="Filed by a person with /finding in a review thread">
    <MessageSquare size={11} aria-hidden="true" /> from discussion
  </span>
)}
```

Import `MessageSquare` from `lucide-react` — **never an emoji**. `pill--muted` because provenance is context, not a problem: `--warn` would read as something needing attention and `--crit` as an outage.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run src/render.finding-origin.test.tsx && npx tsc --noEmit`
Expected: PASS, and `tsc` silent.

- [ ] **Step 5: Commit**

```bash
git add spire-ui/
git commit -m "Mark findings a person filed from a discussion"
```

---

### Task 13: Make `/finding` discoverable

The trigger is a command nobody knows exists. Two placements, neither costing a call.

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/FollowUpWorker.java` (`capNoticeText` ~line 168)
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ReviewWorker.java` (summary assembly — locate the `"### Code Spire review"` header)
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/FollowUpWorkerTest.java` and the existing summary test

**Interfaces:**
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

```java
@Test
void theTurnCapNoticePointsAtTheCommandThatOutlivesIt() {
    // A capped thread is exactly where a human has been discussing something worth filing, and the
    // notice is the last thing the bot says there.
    String text = FollowUpWorker.capNoticeText(4);

    assertThat(text).contains("/finding");
}
```

And in the summary test (find the one asserting `"### Code Spire review"` — `CLAUDE.md` records it is also asserted in `FindingConversation.test.ts`):

```java
@Test
void theSummaryNamesTheFindingCommandOnce() {
    String summary = ReviewWorker.renderSummary(/* the existing test's arguments */);

    assertThat(summary).contains("/finding");
}
```

Read the existing summary test to get the real method name and arguments; do not invent `renderSummary` if the code calls it something else.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-review-worker:test --tests '*FollowUpWorkerTest*'`
Expected: FAIL — text does not contain "/finding".

- [ ] **Step 3: Add the clauses**

```java
    static String capNoticeText(int turnCap) {
        return "I've replied " + turnCap + " times in this thread, so I'll hand it back to the team "
                + "rather than keep going. @-mention me if you still need something here, or run "
                + "`/finding` to file what we discussed as a tracked finding.";
    }
```

In the summary footer, one line:

```
Run `/finding` in any inline thread to file what you discussed there as a tracked finding.
```

**Check `FindingConversation.test.ts` in `spire-ui`** before changing the summary — it asserts on the header. If it asserts the full body, update it in the same commit.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-review-worker:test && cd spire-ui && npx vitest run`
Expected: PASS both.

- [ ] **Step 5: Verify the whole feature end to end**

Run: `./gradlew testFast && ./gradlew testServices && cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: all PASS. Record the new totals against the 1256 Java / 323 vitest baseline.

- [ ] **Step 6: Commit**

```bash
git add spire-review-worker/ spire-ui/
git commit -m "Point at /finding where a person is most likely to want it"
```

---

# Phase 3 — Preview against a real review

### Task 14: Render a candidate template against a real review

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptSampleRenderer.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/prompt/PromptSampleRendererTest.java`

**Interfaces:**
- Consumes: `PromptRenderer.render(PromptTemplate, Map<String,String>)` from `spire-llm`; `ProviderClients` for a `DiffSource`; `ReviewProjection` for the review's row; the context `BlobStore`.
- Produces: `PromptSampleRenderer.render(PromptKind kind, String system, String body, String reviewId)` → `PromptValidation.PromptPreview`. Throws `NotFoundException` when the review does not exist; throws `PromptSampleUnavailable(String reason)` when the diff cannot be fetched.

- [ ] **Step 1: Write the failing test**

```java
@QuarkusTest
class PromptSampleRendererTest {

    @Test
    void rendersTheCandidateTemplateAgainstTheReviewsRealDiff() {
        String reviewId = registerReviewWithDiff("src/Foo.java", "TESTUSDT placeholder line");

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.REVIEW,
                "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        assertThat(preview.user()).contains("src/Foo.java");
        assertThat(preview.user()).doesNotContain("«diff inserted here»");
    }

    @Test
    void showsTheUntrustedDataFenceTheRendererApplies() {
        // The annotated preview shows no fence at all, so an operator cannot see the injection
        // boundary their template's variables sit inside.
        String reviewId = registerReviewWithDiff("src/Foo.java", "TESTUSDT placeholder line");

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.REVIEW,
                "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        assertThat(preview.user()).contains("BEGIN_UNTRUSTED_DATA").contains("END_UNTRUSTED_DATA");
    }

    @Test
    void clipsExactlyAsARealReviewWould() {
        // A large diff is clipped before it reaches the model. An operator cannot currently see
        // that happening, which is half the reason this preview exists.
        String reviewId = registerReviewWithDiff("src/Big.java", oversizedButObviouslySyntheticDiff());

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.REVIEW,
                "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        assertThat(preview.user()).contains("…");
        assertThat(preview.user().length()).isLessThan(oversizedButObviouslySyntheticDiff().length());
    }

    @Test
    void anUnfetchableDiffFailsWithAReasonRatherThanAnEmptyPanel() {
        String reviewId = registerReviewWhoseDiffFetchFails();

        assertThatThrownBy(() -> renderer.render(PromptKind.REVIEW, "You are a reviewer.",
                "Diff:\n{{diff}}", reviewId))
                .isInstanceOf(PromptSampleRenderer.PromptSampleUnavailable.class)
                .hasMessageContaining("diff");
    }
}
```

`oversizedButObviouslySyntheticDiff()` must be **obviously synthetic** — repeat a line like `+ // CANARY line 0001` enough times to exceed the 24,000-token clip. Never a plausible-looking real diff: this text ends up in a rendered UI panel, which is exactly what the no-synthetic-data rule covers.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptSampleRendererTest*'`
Expected: FAIL — cannot find symbol `PromptSampleRenderer`.

- [ ] **Step 3: Implement**

```java
package dev.codespire.orchestrator.prompt;

/**
 * Renders a candidate prompt template against a REAL review the deployment already has.
 *
 * <p>Deliberately not a bundled sample diff. A shipped sample is fabricated data rendered in the UI
 * as though it were real input; a real review's diff is, by definition, not. It is also the better
 * preview: an operator wants to see their template against their own code, and running the
 * production {@link PromptRenderer} makes token clipping and untrusted-data fencing visible, neither
 * of which the annotated preview shows.
 *
 * <p>Makes no LLM call — one diff fetch and one blob read. There is nothing to charge and no spend
 * gate to consult.
 */
@ApplicationScoped
public class PromptSampleRenderer {

    /** The review exists but its input could not be assembled — shown to the operator as the reason
     *  the panel fell back to the annotated preview, rather than an empty box. */
    public static class PromptSampleUnavailable extends RuntimeException {
        public PromptSampleUnavailable(String message) {
            super(message);
        }
    }

    public PromptValidation.PromptPreview render(PromptKind kind, String system, String body,
                                                 String reviewId) {
        // 1. Load the review row (404 if absent).
        // 2. Resolve its provider and DiffSource via ProviderClients, keyed by the stored
        //    provider_type — the same disambiguation ReviewProviderResolver does, because one
        //    workspace name can be registered on two SCMs.
        // 3. Re-fetch the diff by commit (ADR-011: diffs are never persisted).
        // 4. Load the assembled context blob if the review has a contextRef; empty string if not.
        // 5. Build the value map with the SAME keys the real builders use — for REVIEW:
        //    pr_title, pr_description, context, prior_findings, diff.
        // 6. PromptRenderer.render(new PromptTemplate(kind, system, body), values).prompt()
        //    and return its system + user text as a PromptPreview.
    }
}
```

Write the six numbered steps as real code — read `ReviewPromptBuilder`, `ReconcilePrompt` and `FollowUpPrompt` to copy each kind's exact value-map keys, and wrap any `ScmApiException` from the diff fetch in `PromptSampleUnavailable` with the status in the message. For RECONCILE and FOLLOWUP, supply `prior_findings` / `thread` / `anchor` from the review's stored data where it exists and an explicit `(none)` where it does not — never invented content.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptSampleRendererTest*'`
Expected: PASS, all four tests.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -F - <<'EOF'
Render a prompt preview against a real review

The existing preview annotates variable slots rather than filling them,
deliberately, so the panel contains no fabricated data. A bundled sample
diff would reintroduce exactly that -- plausible-looking text rendered
in the UI as real input.

Rendering a review the deployment already has satisfies the same rule
and is a better preview: it runs the production renderer, so token
clipping and the untrusted-data fence become visible. Neither shows in
the annotated form, so an operator cannot currently see that a large
diff is clipped before it reaches the model.

No LLM call -- one diff fetch and one blob read.
EOF
```

---

### Task 15: Expose the sample preview on the API

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptResource.java:80-90`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptInput.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/prompt/PromptResourceSamplePreviewTest.java`

**Interfaces:**
- Consumes: `PromptSampleRenderer.render(...)` (Task 14).
- Produces: `PromptInput(String system, String body, String reviewId)` — `reviewId` nullable; `PreviewResult(String system, String user, List<String> errors, String sampleReviewId, String unavailableReason)`.

- [ ] **Step 1: Write the failing test**

```java
@QuarkusTest
class PromptResourceSamplePreviewTest {

    @Test
    void previewWithoutAReviewIdStaysAnnotated() {
        PreviewResult result = preview("review", "You are a reviewer.", "Diff:\n{{diff}}", null);

        assertThat(result.user()).contains("«diff inserted here»");
        assertThat(result.sampleReviewId()).isNull();
    }

    @Test
    void previewWithAReviewIdRendersThatReview() {
        String reviewId = registerReviewWithDiff("src/Foo.java", "CANARY line");

        PreviewResult result = preview("review", "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        assertThat(result.user()).contains("src/Foo.java");
        assertThat(result.sampleReviewId()).isEqualTo(reviewId);
        assertThat(result.unavailableReason()).isNull();
    }

    @Test
    void anUnfetchableDiffFallsBackToAnnotatedAndSaysWhy() {
        // An empty panel would read as a broken preview. The reason is what makes it actionable.
        String reviewId = registerReviewWhoseDiffFetchFails();

        PreviewResult result = preview("review", "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        assertThat(result.user()).contains("«diff inserted here»");
        assertThat(result.unavailableReason()).isNotBlank();
    }

    @Test
    void aViewerCannotPreview() {
        // Class-level @RolesAllowed("spire-admin") already covers this, and it must keep covering it:
        // the preview now renders a real pull request's source code into its response.
        assertThat(previewAs("spire-viewer", "review", "You are a reviewer.", "Diff:\n{{diff}}", null))
                .isEqualTo(403);
    }
}
```

Use the module's existing REST-test harness (`@TestSecurity` or the RestAssured setup its sibling resource tests use) — read one before writing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptResourceSamplePreviewTest*'`
Expected: FAIL — `PromptInput` has no `reviewId`.

- [ ] **Step 3: Implement**

```java
public record PromptInput(String system, String body, String reviewId) {

    /** No sample review — the annotated preview, and every save, which never carries one. */
    public PromptInput(String system, String body) {
        this(system, body, null);
    }
}
```

```java
    public record PreviewResult(String system, String user, List<String> errors,
                                String sampleReviewId, String unavailableReason) {
    }

    @POST
    // Admin-only via the class annotation, and that matters more now: with a reviewId this renders a
    // real pull request's source code into the response. It writes nothing and calls no LLM — the
    // POST is only because the body carries the draft.
    @Path("/{kind}/preview")
    public PreviewResult preview(@PathParam("kind") String kind, PromptInput in) {
        PromptKind promptKind = parse(kind);
        requireBody(in);
        List<String> errors = PromptValidation.validate(promptKind, in.system(), in.body());
        if (in.reviewId() == null || in.reviewId().isBlank()) {
            PromptValidation.PromptPreview p =
                    PromptValidation.preview(promptKind, in.system(), in.body());
            return new PreviewResult(p.system(), p.user(), errors, null, null);
        }
        try {
            PromptValidation.PromptPreview p =
                    sampleRenderer.render(promptKind, in.system(), in.body(), in.reviewId());
            return new PreviewResult(p.system(), p.user(), errors, in.reviewId(), null);
        } catch (PromptSampleRenderer.PromptSampleUnavailable unavailable) {
            // Fall back to the annotated preview WITH the reason. An empty panel reads as a broken
            // feature; the reason tells the operator to pick a different review.
            PromptValidation.PromptPreview p =
                    PromptValidation.preview(promptKind, in.system(), in.body());
            return new PreviewResult(p.system(), p.user(), errors, null, unavailable.getMessage());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -m "Accept a review id on the prompt preview endpoint"
```

---

### Task 16: The preview panel picks a review

**Files:**
- Create: `spire-ui/src/components/PromptSamplePicker.tsx`
- Modify: `spire-ui/src/api.ts:929-1000`
- Modify: `spire-ui/src/components/PromptDetail.tsx`
- Test: `spire-ui/src/components/PromptSamplePicker.test.tsx`

**Interfaces:**
- Consumes: `previewPrompt(kind, system, body, reviewId?)`; the existing reviews-list fetch.
- Produces: `PromptPreview` gains `sampleReviewId: string | null` and `unavailableReason: string | null`.

- [ ] **Step 1: Write the failing test**

```tsx
describe('PromptSamplePicker', () => {
  it('previews against no review by default', async () => {
    const preview = vi.spyOn(api, 'previewPrompt').mockResolvedValue(annotatedPreview());
    render(<PromptSamplePicker kind="review" system="s" body="b" />);

    await userEvent.click(screen.getByRole('button', { name: /preview/i }));

    expect(preview).toHaveBeenCalledWith('review', 's', 'b', undefined);
  });

  it('previews against the selected review', async () => {
    const preview = vi.spyOn(api, 'previewPrompt').mockResolvedValue(samplePreview());
    vi.spyOn(api, 'fetchReviews').mockResolvedValue([reviewRow('acme/widgets#7')]);
    render(<PromptSamplePicker kind="review" system="s" body="b" />);

    await userEvent.selectOptions(await screen.findByLabelText(/sample/i), 'acme/widgets#7');
    await userEvent.click(screen.getByRole('button', { name: /preview/i }));

    expect(preview).toHaveBeenCalledWith('review', 's', 'b', 'acme/widgets#7');
  });

  it('shows why a sample was unavailable instead of an empty panel', async () => {
    vi.spyOn(api, 'previewPrompt').mockResolvedValue({
      system: 's', user: '«diff inserted here»', errors: [],
      sampleReviewId: null, unavailableReason: 'diff fetch failed (404)',
    });
    render(<PromptSamplePicker kind="review" system="s" body="b" />);

    await userEvent.click(screen.getByRole('button', { name: /preview/i }));

    expect(await screen.findByText(/diff fetch failed \(404\)/)).toBeInTheDocument();
  });
});
```

`vitest.setup.ts` already calls `vi.restoreAllMocks()` — a fix made because `vi.spyOn` re-wraps the same module function and leaked call history between tests in a file, so `not.toHaveBeenCalled()` was passing on test ordering. Do not add per-test cleanup; rely on the central one.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/PromptSamplePicker.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

Extend the API types and function:

```ts
export interface PromptPreview {
  system: string;
  user: string;
  errors: string[];
  // The review this was rendered against, or null for the annotated (no-data) preview.
  sampleReviewId: string | null;
  // Why a requested sample could not be rendered — shown beside the annotated fallback so an
  // empty-looking panel is never mistaken for a broken preview.
  unavailableReason: string | null;
}

export async function previewPrompt(
  kind: string, system: string, body: string, reviewId?: string,
): Promise<PromptPreview> {
  const res = await apiFetch(`/api/prompts/${encodeURIComponent(kind)}/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ system, body, reviewId }),
  });
  if (!res.ok) return throwResponse(res, 'Failed to preview prompt');
  return res.json();
}
```

Build `PromptSamplePicker` as a `<select>` of recent reviews (label: `workspace/slug#pr`) plus the existing Preview button, defaulting to "No sample — show variable slots". Render `unavailableReason` in a muted line above the preview text when present. Mount it in `PromptDetail`'s preview area, replacing the direct `previewPrompt` call.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, `tsc` silent.

- [ ] **Step 5: Commit**

```bash
git add spire-ui/
git commit -m "Let the prompt preview run against a chosen review"
```

---

# Phase 4 — The default-migration story

### Task 17: Record which built-in default a customization forked from

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V33__prompt_template_base.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptRegistry.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/prompt/PromptRegistryDriftTest.java`

**Interfaces:**
- Produces: `PromptRegistry.drift(PromptKind kind)` → `Drift(boolean baseKnown, boolean defaultDrifted, String baseSystem, String baseBody)`; `PromptRegistry.acceptCurrentDefault(PromptKind kind)` → `void` (re-stamps the ancestor without touching the customization).

- [ ] **Step 1: Write the failing test**

```java
@QuarkusTest
class PromptRegistryDriftTest {

    @Test
    void aFreshCustomizationHasNotDrifted() {
        registry.save(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW);

        assertThat(drift.baseKnown()).isTrue();
        assertThat(drift.defaultDrifted()).isFalse();
    }

    @Test
    void theStoredAncestorIsTheBuiltInDefaultNotTheCustomization() {
        registry.save(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW);

        assertThat(drift.baseSystem()).isEqualTo(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system());
        assertThat(drift.baseSystem()).isNotEqualTo("My persona");
    }

    @Test
    void aRowWithNoRecordedAncestorReportsUnknownNotUpToDate() {
        // Every row written before V33. Reporting "up to date" would be a confident claim about
        // state nobody recorded.
        insertLegacyRowWithoutBase(PromptKind.REVIEW, "Old persona", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW);

        assertThat(drift.baseKnown()).isFalse();
        assertThat(drift.defaultDrifted()).isFalse();   // unknowable, so not asserted either way
    }

    @Test
    void acceptingTheCurrentDefaultClearsDriftAndKeepsTheCustomization() {
        insertRowWithBase(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");
        assertThat(registry.drift(PromptKind.REVIEW).defaultDrifted()).isTrue();

        registry.acceptCurrentDefault(PromptKind.REVIEW);

        assertThat(registry.drift(PromptKind.REVIEW).defaultDrifted()).isFalse();
        assertThat(registry.effective(PromptKind.REVIEW).system()).isEqualTo("My persona");
    }

    @Test
    void anUncustomizedKindNeverReportsDrift() {
        assertThat(registry.drift(PromptKind.RECONCILE).defaultDrifted()).isFalse();
        assertThat(registry.drift(PromptKind.RECONCILE).baseKnown()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptRegistryDriftTest*'`
Expected: FAIL — cannot find symbol `drift`.

- [ ] **Step 3: Migrate and implement**

```sql
-- V33__prompt_template_base.sql
-- The built-in default AS IT STOOD when the operator saved this override -- the common ancestor a
-- customization forked from.
--
-- Without it, an improvement to the shipped prompt and the operator's own edits are
-- indistinguishable, which is why the only answer used to be reset-to-default (discarding the
-- customization wholesale). NULL means the row predates this column: the ancestor is UNKNOWN, which
-- is not the same as "matches the current default" and must not be reported as such.
ALTER TABLE prompt_template ADD COLUMN base_system_text TEXT;
ALTER TABLE prompt_template ADD COLUMN base_body_text   TEXT;
```

In `PromptRegistry.save`, stamp the ancestor from `PromptCatalog.defaultTemplate(kind)` on insert **and** on update — an operator re-saving is re-forking from whatever ships now. Add:

```java
    /** Whether the built-in default has moved since this kind was customized. */
    public record Drift(boolean baseKnown, boolean defaultDrifted, String baseSystem, String baseBody) {
    }

    public Drift drift(PromptKind kind) {
        Optional<Row> stored = row(kind);
        if (stored.isEmpty()) {
            return new Drift(true, false, null, null);   // not customized: nothing to drift from
        }
        Row r = stored.get();
        if (r.baseSystem() == null && r.baseBody() == null) {
            return new Drift(false, false, null, null);  // predates V33 — unknown, not up to date
        }
        PromptTemplate current = PromptCatalog.defaultTemplate(kind);
        boolean drifted = !current.system().equals(r.baseSystem())
                || !current.body().equals(r.baseBody());
        return new Drift(true, drifted, r.baseSystem(), r.baseBody());
    }

    /** Keep the customization, stop reporting drift: re-stamp the ancestor to what ships now. */
    @Transactional
    public void acceptCurrentDefault(PromptKind kind) {
        PromptTemplate current = PromptCatalog.defaultTemplate(kind);
        // ... UPDATE prompt_template SET base_system_text = ?, base_body_text = ? WHERE kind = ?
    }
```

Extend the private `Row` record with `baseSystem`/`baseBody` and widen the `SELECT` in `row(kind)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptRegistryDriftTest*'`
Expected: PASS, all five tests.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -F - <<'EOF'
Record which built-in default a customization forked from

A customized template was a fork with no recorded common ancestor, so an
improvement to the shipped prompt and the operator's own edits could not
be told apart -- which is why the only escape hatch was reset-to-default,
discarding the customization wholesale.

Storing the default as it stood at save time makes "has the shipped
prompt moved since you customized this" computable. Rows written before
this column report an unknown ancestor rather than a confident
up-to-date, which nobody recorded.
EOF
```

---

### Task 18: Surface drift on the API

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptView.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptRegistry.java` (`effective`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptResource.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/prompt/PromptResourceDriftTest.java`

**Interfaces:**
- Consumes: `PromptRegistry.drift/acceptCurrentDefault` (Task 17).
- Produces: `PromptView` gains `boolean baseKnown, boolean defaultDrifted, String currentDefaultSystem, String currentDefaultBody, String baseSystem, String baseBody`; `POST /api/prompts/{kind}/accept-default` → 204.

- [ ] **Step 1: Write the failing test**

```java
@QuarkusTest
class PromptResourceDriftTest {

    @Test
    void aDriftedKindSaysSoAndCarriesBothSidesOfTheDiff() {
        insertRowWithBase(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");

        PromptView view = get("/api/prompts/review");

        assertThat(view.defaultDrifted()).isTrue();
        assertThat(view.baseSystem()).isEqualTo("AN OLDER SHIPPED PERSONA");
        assertThat(view.currentDefaultSystem())
                .isEqualTo(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system());
    }

    @Test
    void acceptDefaultClearsTheFlagWithoutChangingTheEffectiveTemplate() {
        insertRowWithBase(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");

        assertThat(post("/api/prompts/review/accept-default")).isEqualTo(204);

        PromptView view = get("/api/prompts/review");
        assertThat(view.defaultDrifted()).isFalse();
        assertThat(view.system()).isEqualTo("My persona");
    }

    @Test
    void aViewerCannotAcceptTheDefault() {
        assertThat(postAs("spire-viewer", "/api/prompts/review/accept-default")).isEqualTo(403);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptResourceDriftTest*'`
Expected: FAIL — `PromptView` has no `defaultDrifted`.

- [ ] **Step 3: Implement**

Extend `PromptView` with the six components and populate them in `PromptRegistry.effective` from `drift(kind)` plus `PromptCatalog.defaultTemplate(kind)`. Add:

```java
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{kind}/accept-default")
    public Response acceptDefault(@PathParam("kind") String kind) {
        registry.acceptCurrentDefault(parse(kind));
        return Response.noContent().build();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -m "Report prompt-default drift on the prompts API"
```

---

### Task 19: The editor shows what changed and offers two ways out

**Files:**
- Modify: `spire-ui/src/api.ts` (`PromptView`, new `acceptPromptDefault`)
- Modify: `spire-ui/src/components/PromptDetail.tsx`
- Modify: `spire-ui/src/components/PromptsSettings.tsx`
- Test: `spire-ui/src/components/PromptDetail.drift.test.tsx`

**Interfaces:**
- Consumes: the six `PromptView` fields from Task 18.
- Produces: `acceptPromptDefault(kind: string): Promise<void>`.

- [ ] **Step 1: Write the failing test**

```tsx
describe('prompt default drift', () => {
  it('shows what changed in the shipped prompt', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue(driftedView());
    renderWithRouter('/settings/prompts/review');

    expect(await screen.findByText(/the built-in prompt has changed/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /take the new default/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /keep mine/i })).toBeInTheDocument();
  });

  it('keep mine re-stamps the ancestor without touching the text', async () => {
    const accept = vi.spyOn(api, 'acceptPromptDefault').mockResolvedValue();
    const reset = vi.spyOn(api, 'resetPrompt').mockResolvedValue();
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue(driftedView());
    renderWithRouter('/settings/prompts/review');

    await userEvent.click(await screen.findByRole('button', { name: /keep mine/i }));

    expect(accept).toHaveBeenCalledWith('review');
    expect(reset).not.toHaveBeenCalled();
  });

  it('says the ancestor is unknown rather than claiming up to date', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({ ...driftedView(), baseKnown: false, defaultDrifted: false });
    renderWithRouter('/settings/prompts/review');

    expect(await screen.findByText(/customized before default tracking began/i)).toBeInTheDocument();
  });

  it('offers no default diff when there is nothing to diff against', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({ ...driftedView(), baseKnown: false, defaultDrifted: false });
    renderWithRouter('/settings/prompts/review');

    await screen.findByText(/customized before default tracking began/i);
    expect(screen.queryByText(/the built-in prompt has changed/i)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/PromptDetail.drift.test.tsx`
Expected: FAIL — banner text not found.

- [ ] **Step 3: Implement**

Add the six fields to the `PromptView` interface and `acceptPromptDefault` beside `resetPrompt`. In `PromptDetail`, render above the editor:

- When `defaultDrifted`: a banner reading "The built-in prompt has changed since you customized this", a line-level diff of `baseSystem`/`baseBody` against `currentDefaultSystem`/`currentDefaultBody`, and the two buttons. **Take the new default** calls `resetPrompt` behind the existing confirm; **Keep mine** calls `acceptPromptDefault`.
- When `!baseKnown`: a muted line, "Customized before default tracking began — the original built-in text was not recorded, so there is nothing to compare against." Same two buttons, no diff.

Use `AlertTriangle` from `lucide-react` for the drift banner (the file already imports it). In `PromptsSettings`, add a small badge on any kind whose `defaultDrifted` is true.

No auto-merge, and no attention-panel row: the panel deliberately excluded `CREDENTIAL_UNVERIFIED` as wallpaper that belongs inline on its settings page, and a drifted default is not blocking — reviews run fine, the operator is merely missing an improvement.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, `tsc` silent.

- [ ] **Step 5: Commit**

```bash
git add spire-ui/
git commit -m "Show when the built-in prompt has moved under a customization"
```

---

# Phase 5 — Per-repo prompt scope

### Task 20: Re-key `prompt_template` on `(scope, kind)`

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V34__prompt_template_scope.sql`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptScope.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptRegistry.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/prompt/PromptRegistryScopeTest.java`

**Interfaces:**
- Produces:
  - `PromptScope.GLOBAL` → the `"*"` value; `PromptScope.of(RepoRef repo)` → `"workspace/slug"`; `PromptScope.parse(String)` → validated scope, throwing `IllegalArgumentException` on a malformed value.
  - `PromptRegistry.effective(PromptKind kind, String scope)`, `save(PromptKind, String scope, String system, String body)`, `reset(PromptKind, String scope)`, `customized(PromptKind, String scope)`.

- [ ] **Step 1: Write the failing test**

```java
@QuarkusTest
class PromptRegistryScopeTest {

    @Test
    void aRepoOverrideBeatsGlobal() {
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

        assertThat(registry.effective(PromptKind.REVIEW, "acme/widgets").system())
                .isEqualTo("Repo persona");
    }

    @Test
    void globalBeatsTheBuiltInDefault() {
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");

        assertThat(registry.effective(PromptKind.REVIEW, "acme/other").system())
                .isEqualTo("Global persona");
    }

    @Test
    void withNeitherTheBuiltInDefaultApplies() {
        assertThat(registry.effective(PromptKind.REVIEW, "acme/widgets").system())
                .isEqualTo(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system());
    }

    @Test
    void aRepoWithNoRowFallsThroughRatherThanReturningEmpty() {
        // Both directions matter: a test that only checks "repo wins" passes on an implementation
        // that ignores global entirely.
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

        assertThat(registry.effective(PromptKind.REVIEW, "acme/unrelated").system())
                .isEqualTo("Global persona");
    }

    @Test
    void resettingARepoScopeLeavesGlobalAlone() {
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

        registry.reset(PromptKind.REVIEW, "acme/widgets");

        assertThat(registry.effective(PromptKind.REVIEW, "acme/widgets").system())
                .isEqualTo("Global persona");
    }

    @Test
    void anExistingGlobalRowKeepsWorkingAfterTheMigration() {
        insertPreMigrationRow(PromptKind.RECONCILE, "Legacy persona", "{{prior_findings}}\n{{diff}}");

        assertThat(registry.effective(PromptKind.RECONCILE, "acme/widgets").system())
                .isEqualTo("Legacy persona");
    }

    @Test
    void aMalformedScopeIsRejected() {
        assertThatThrownBy(() -> PromptScope.parse("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptRegistryScopeTest*'`
Expected: FAIL — cannot find symbol `PromptScope`.

- [ ] **Step 3: Migrate and implement**

```sql
-- V34__prompt_template_scope.sql
-- Per-repository prompt overrides. scope = '*' for global, else 'workspace/slug'.
--
-- Existing rows take the default and stay global, so nothing changes on upgrade. Resolution is
-- most-specific-wins: repo row, then global row, then the built-in PromptCatalog default.
--
-- A repo row replaces BOTH system and body -- not a per-field merge. Merging would mean an operator
-- editing the global persona silently changed the effective prompt of every repo that had overridden
-- only the body, which is a spooky edit to the instructions a review runs under.
ALTER TABLE prompt_template ADD COLUMN scope TEXT NOT NULL DEFAULT '*';
ALTER TABLE prompt_template DROP CONSTRAINT prompt_template_pkey;
ALTER TABLE prompt_template ADD PRIMARY KEY (scope, kind);
```

```java
package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.scm.RepoRef;

import java.util.regex.Pattern;

/**
 * A prompt override's scope: {@code "*"} for the deployment-wide default, or {@code workspace/slug}
 * for one repository.
 *
 * <p>Validated rather than trusted: the value is a primary-key component that arrives from a REST
 * path, and a scope of {@code "../../x"} would be a stored key nothing could ever address again.
 */
public final class PromptScope {

    public static final String GLOBAL = "*";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._\\-/]*[A-Za-z0-9])?");

    private PromptScope() {
    }

    public static String of(RepoRef repo) {
        return repo.workspace() + "/" + repo.slug();
    }

    public static String parse(String raw) {
        if (GLOBAL.equals(raw)) {
            return GLOBAL;
        }
        if (raw == null || raw.contains("..") || !raw.contains("/") || !SEGMENT.matcher(raw).matches()) {
            throw new IllegalArgumentException("Not a valid prompt scope: '" + raw + "'");
        }
        return raw;
    }
}
```

The segment pattern permits `/` because GitLab namespaces nest (`group/subgroup/project`) — the same reason `spire-scm-gitlab`'s slug parsers were widened. It still forbids `..` and leading/trailing punctuation.

In `PromptRegistry`, thread `scope` through `row`, `save`, `reset` and `customized`, and make `effective(kind, scope)` try the repo row, then the global row, then the default. Keep the old single-argument methods as delegates to `GLOBAL` so nothing else breaks in this task.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptRegistryScopeTest*'`
Expected: PASS, all seven tests.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -F - <<'EOF'
Key prompt overrides on scope as well as kind

A repository can now override a prompt without the operator maintaining
one global template that has to serve every language in the workspace.
Resolution is most-specific-wins: repo, then global, then the built-in
default. Existing rows take the '*' scope, so nothing changes on
upgrade.

A repo row replaces the whole template rather than merging field by
field. Merging would let an edit to the global persona silently change
the effective prompt of every repo that had overridden only the body.

.codespire is unaffected and still the right tool for rules a team
states in its own repository: it is contributor-owned, rides in the
fenced untrusted slot, and can only add text. A prompt override is
operator-owned and can change structure -- a persona's priority order,
which variables appear at all.
EOF
```

---

### Task 21: The worker gets the repo's template

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/WorkerPromptTemplates.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java` and `ConversationSaga.java:130` (the `promptTemplates.forKind(...)` call sites)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/prompt/WorkerPromptTemplatesTest.java`

**Interfaces:**
- Consumes: `PromptRegistry.customized(PromptKind, String scope)` and `PromptScope.of(RepoRef)` (Task 20).
- Produces: `WorkerPromptTemplates.forKind(PromptKind kind, RepoRef repo)` → `PromptTemplate` or null.

- [ ] **Step 1: Write the failing test**

```java
@Test
void packsTheRepoOverrideWhenOneExists() {
    registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

    assertThat(templates.forKind(PromptKind.REVIEW, new RepoRef("acme", "widgets")).system())
            .isEqualTo("Repo persona");
}

@Test
void fallsBackToGlobalForARepoWithNoOverride() {
    registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");

    assertThat(templates.forKind(PromptKind.REVIEW, new RepoRef("acme", "other")).system())
            .isEqualTo("Global persona");
}

@Test
void packsNothingWhenNeitherScopeIsCustomized() {
    // null keeps the common case off the command entirely: the worker uses the built-in default.
    assertThat(templates.forKind(PromptKind.REVIEW, new RepoRef("acme", "widgets"))).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*WorkerPromptTemplatesTest*'`
Expected: FAIL — `forKind` does not take a `RepoRef`.

- [ ] **Step 3: Implement**

```java
    /**
     * The override to attach to a command, most specific first: the repository's, else the global
     * one, else null so the worker uses the built-in default (no command bloat in the common case).
     */
    public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
        return registry.customized(kind, PromptScope.of(repo))
                .or(() -> registry.customized(kind, PromptScope.GLOBAL))
                .orElse(null);
    }
```

Update every call site to pass the repo. `ConversationSaga` has `e.repo()`; `ResultSaga`'s `GenerateReview` construction has the repo on the command it is building.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -m "Resolve a command's prompt against its repository first"
```

---

### Task 22: Scope on the prompts API

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptResource.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptView.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/prompt/PromptResourceScopeTest.java`

**Interfaces:**
- Consumes: `PromptScope.parse`, the scope-aware registry (Task 20).
- Produces: every prompt endpoint accepts `?scope=` (default `*`); `PromptView` gains `String scope` and `String inheritedFrom` (`"repo"`, `"global"` or `"default"`); `GET /api/prompts/scopes` → `List<String>` of repos the orchestrator has seen.

- [ ] **Step 1: Write the failing test**

```java
@Test
void readingAtARepoScopeSaysWhereTheTextCameFrom() {
    registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");

    PromptView view = get("/api/prompts/review?scope=acme%2Fwidgets");

    assertThat(view.scope()).isEqualTo("acme/widgets");
    assertThat(view.inheritedFrom()).isEqualTo("global");
    assertThat(view.system()).isEqualTo("Global persona");
}

@Test
void savingAtARepoScopeDoesNotTouchGlobal() {
    registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");

    put("/api/prompts/review?scope=acme%2Fwidgets", "Repo persona", "Diff:\n{{diff}}");

    assertThat(get("/api/prompts/review?scope=*").system()).isEqualTo("Global persona");
    assertThat(get("/api/prompts/review?scope=acme%2Fwidgets").inheritedFrom()).isEqualTo("repo");
}

@Test
void aMalformedScopeIs400NotAStoredKeyNobodyCanAddress() {
    assertThat(putStatus("/api/prompts/review?scope=..%2F..%2Fetc", "x", "{{diff}}")).isEqualTo(400);
}

@Test
void scopesListsRepositoriesTheOrchestratorHasSeen() {
    registerReview("acme", "widgets", 7);

    assertThat(get("/api/prompts/scopes", new TypeRef<List<String>>() {}))
            .contains("acme/widgets");
}

@Test
void aViewerCannotListScopes() {
    // Every registry read is admin-only (ADR-022): a listing is an inventory of every repository
    // the deployment reaches.
    assertThat(getAs("spire-viewer", "/api/prompts/scopes")).isEqualTo(403);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*PromptResourceScopeTest*'`
Expected: FAIL — unknown query parameter, `scope()` missing on `PromptView`.

- [ ] **Step 3: Implement**

Add `@QueryParam("scope") @DefaultValue(PromptScope.GLOBAL) String scope` to `list`, `get`, `save`, `reset` and `preview`, running it through `PromptScope.parse` inside the existing `badRequest` handling so a malformed value is a 400 rather than a 500. Populate `inheritedFrom` in `PromptRegistry.effective(kind, scope)` — `"repo"` when the repo row supplied the text, `"global"` when the global row did, `"default"` when neither.

Add:

```java
    /** Repositories this deployment has reviewed — the scopes an override can be written at.
     *  Read from the orchestrator's own review rows, NOT the gateway's webhook_repo: that table
     *  belongs to another service behind its own URL prefix and session (ADR-022), and a settings
     *  dropdown is not a reason to couple them. A repo nobody has reviewed is also one there is
     *  nothing to preview a template against. */
    @GET
    @Path("/scopes")
    public List<String> scopes() {
        return projection.knownRepoScopes();
    }
```

Add `knownRepoScopes()` to `ReviewProjection` as `SELECT DISTINCT workspace || '/' || slug FROM review_status ORDER BY 1`, matching the file's existing query style and degrading to `List.of()` with a `LOG.warnf` on `SQLException`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/
git commit -m "Accept a scope on every prompt endpoint"
```

---

### Task 23: The scope selector

**Files:**
- Create: `spire-ui/src/components/PromptScopePicker.tsx`
- Modify: `spire-ui/src/api.ts`, `spire-ui/src/components/PromptsSettings.tsx`, `spire-ui/src/components/PromptDetail.tsx`
- Test: `spire-ui/src/components/PromptScopePicker.test.tsx`

**Interfaces:**
- Consumes: `fetchPromptScopes(): Promise<string[]>`; every prompt API function gains an optional trailing `scope` argument defaulting to `'*'`.
- Produces: `PromptView` gains `scope: string` and `inheritedFrom: 'repo' | 'global' | 'default'`.

- [ ] **Step 1: Write the failing test**

```tsx
describe('PromptScopePicker', () => {
  it('defaults to global', async () => {
    vi.spyOn(api, 'fetchPromptScopes').mockResolvedValue(['acme/widgets']);
    const onChange = vi.fn();
    render(<PromptScopePicker value="*" onChange={onChange} />);

    expect(await screen.findByDisplayValue(/global/i)).toBeInTheDocument();
  });

  it('lists the repositories the deployment has seen', async () => {
    vi.spyOn(api, 'fetchPromptScopes').mockResolvedValue(['acme/widgets', 'acme/tools']);
    render(<PromptScopePicker value="*" onChange={vi.fn()} />);

    expect(await screen.findByRole('option', { name: 'acme/widgets' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'acme/tools' })).toBeInTheDocument();
  });

  it('says which level the shown text actually came from', async () => {
    // A reader who cannot tell at a glance which text a review will use has a worse tool than the
    // global-only one this replaces.
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({
      ...baseView(), scope: 'acme/widgets', inheritedFrom: 'global', customized: false,
    });
    renderWithRouter('/settings/prompts/review?scope=acme/widgets');

    expect(await screen.findByText(/inherited from global/i)).toBeInTheDocument();
  });

  it('marks a template overridden at this repo', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({
      ...baseView(), scope: 'acme/widgets', inheritedFrom: 'repo', customized: true,
    });
    renderWithRouter('/settings/prompts/review?scope=acme/widgets');

    expect(await screen.findByText(/overridden for this repository/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/PromptScopePicker.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

Add `scope` and `inheritedFrom` to the `PromptView` interface, `fetchPromptScopes`, and an optional `scope = '*'` argument threaded onto every prompt API call as `?scope=${encodeURIComponent(scope)}`. Build `PromptScopePicker` as a `<select>` with `Global (all repositories)` first, then each scope. Hold the selection in the URL query string so a reload and a shared link keep it.

In `PromptDetail`, render a line under the title stating the provenance: **Overridden for this repository**, **Inherited from global**, or **Built-in default**. In `PromptsSettings`, show per-kind which scope supplies each kind at the current selection.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, `tsc` silent.

- [ ] **Step 5: Full verification**

Run: `./gradlew testFast && ./gradlew testServices && cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: all PASS. Compare totals against the 1256 Java / 323 vitest baseline and record them.

- [ ] **Step 6: Update the documentation**

- `docs/ROADMAP.md`: move E16 and E17 from the open table into Delivered with the date, and remove the "17 techdebt items" reference to `4-4-conversation-derived-findings.md`.
- `techdebt/global/4-4-conversation-derived-findings.md`: delete it.
- `docs/REPO-RULES.md`: the line stating the operator's prompt template "is global" is now false. Correct it, and state when to reach for `.codespire` versus a per-repo prompt.
- `CLAUDE.md`: add a Status entry.
- `docs/SMOKE-TEST.md`: add **Mode N** covering `/finding` on all three SCMs — file at a line, confirm the reply, confirm the finding appears in the Findings card marked as from a discussion, push a fix, confirm it reconciles to RESOLVED.

- [ ] **Step 7: Commit**

```bash
git add spire-ui/ docs/ techdebt/ CLAUDE.md
git commit -m "Add a scope selector to the prompt settings"
```

---

## Self-review notes

**Spec coverage.** Every spec section maps to tasks: §1.1→T1, §1.2→T2–4, §1.3→T5, §1.4→T2/T3 commits, §2.1–2.3→T9, §2.4→T8, §2.5→T7, §2.6→T7/T8/T10, §2.7→T11/T13, §2.8→T8/T12, §3.1–3.3→T20/T21, §3.4→T23, §4→T14–16, §5→T17–19, §6→the named assertions throughout, §7→the phase map.

**Two changes this plan makes to the spec**, both discovered while writing tasks:

1. **`ManualCommandReceived` also needs `commentId`** (T10). The spec's §2.5 idempotency key is the triggering comment, but the event had no comment id — the key would have been unavailable at the only place that can use it. Task 1 should add all three components together; it is written as two so the wire change and the reason for the third are separately reviewable.
2. **`ConfirmFinding` carries `triggeringCommentId`** (T11), for the worker's claim key. The spec's §2.7 sketch omitted it.

**Known cost.** `ReviewState` gaining a component touches every construction site in `ReviewLifecycle`. That is compiler-guided and safe, but it is the largest mechanical edit in the plan — expect Task 7 to touch more lines than its diff suggests.
