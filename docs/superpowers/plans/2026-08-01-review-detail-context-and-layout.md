# Review Detail Context & Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a review's injected context visible on its detail page, put Metadata above Model usage, and turn the event stream into collapsible newest-first runs.

**Architecture:** Context items are read from the already-stored `worker.context_blob` (encrypted, keyed by `review_id`) and served by the worker over a new REST endpoint, because no other service holds a review's `contextRef`. The PR description is fetched live from the SCM by the orchestrator. The UI merges the two through a Vite proxy, exactly as it already merges gateway and orchestrator data.

**Tech Stack:** Java 25 / Quarkus 3.36 / Gradle Kotlin DSL, JAX-RS + RestAssured, React 19 / TypeScript / Vite / vitest.

## Global Constraints

- Java: 4-space indent, explicit types over `var`, constructor injection, max 3 method parameters.
- TypeScript: 2-space indent, `interface` over `type` for object shapes.
- Transport types are named `*Dto`, `*View`, or `*Payload` only. Read-only projections narrower than their entity use `*View`.
- Icons come from `lucide-react`. **Never emoji.**
- No new user-visible occurrences of the name "Code Spire".
- Nothing new is persisted: no migration, no change to any event record.
- Commit messages: imperative mood, max 72 chars on the first line, no mention of AI/agentic authoring, no `Co-Authored-By` trailers, no model or vendor names.
- Do not stage, commit, or modify `build.gradle.kts` at the repository root — it is the user's own uncommitted work.

---

### Task 1: Look a context blob up by review

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/adapters/PostgresBlobStore.java`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/adapters/PostgresBlobStoreByReviewTest.java` (create)

**Interfaces:**
- Consumes: existing `PostgresBlobStore` with `put(Kind, String, byte[])`, `get(BlobRef)`, `delete(BlobRef)`, `deleteByReview(String)`; `BlobStore.BlobRef(String key)`; `BlobStore.Kind.CONTEXT`.
- Produces: `public byte[] getByReview(String reviewId)` — returns the decrypted payload of the newest blob owned by `reviewId`, or `null` when the review has none.

Why this is needed: `contextRef` travels on `ContextAssembled` into `GenerateReview` and is never projected into any read model, so nothing outside the worker can name a blob by key. `review_id` is a first-class indexed column on `context_blob` for exactly this reason.

- [ ] **Step 1: Write the failing test**

Create `spire-review-worker/src/test/java/dev/codespire/worker/adapters/PostgresBlobStoreByReviewTest.java`:

```java
package dev.codespire.worker.adapters;

import dev.codespire.contract.port.BlobStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class PostgresBlobStoreByReviewTest {

    @Inject
    PostgresBlobStore store;

    @Test
    void returnsTheDecryptedPayloadForAReview() {
        String reviewId = "review::acme/widgets#901";
        byte[] payload = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        store.put(BlobStore.Kind.CONTEXT, reviewId, payload);

        assertArrayEquals(payload, store.getByReview(reviewId));

        store.deleteByReview(reviewId);
    }

    /** A review that resolved nothing writes no blob at all — the normal path with no provider. */
    @Test
    void returnsNullWhenTheReviewHasNoBlob() {
        assertNull(store.getByReview("review::acme/widgets#902"));
    }

    /** The lookup key is the owner, so one review can never read another's context. */
    @Test
    void doesNotReturnABlobOwnedByAnotherReview() {
        String mine = "review::acme/widgets#903";
        String theirs = "review::acme/widgets#904";
        store.put(BlobStore.Kind.CONTEXT, theirs, "{\"items\":[1]}".getBytes(StandardCharsets.UTF_8));

        assertNull(store.getByReview(mine));

        store.deleteByReview(theirs);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-review-worker:test --tests '*PostgresBlobStoreByReviewTest*'`
Expected: FAIL to compile — `cannot find symbol: method getByReview(String)`.

- [ ] **Step 3: Write minimal implementation**

Add to `PostgresBlobStore`, next to `get(BlobRef)`:

```java
    /**
     * The newest context blob a review owns, decrypted, or null when it owns none.
     *
     * <p>Keyed by review rather than by {@code context_id} because no caller outside this service
     * knows a review's contextRef — it rides on ContextAssembled into GenerateReview and is never
     * projected. A re-run deletes the prior blob before writing, so ordering only matters if a
     * delete ever fails; newest-first keeps the answer current rather than stale.
     */
    public byte[] getByReview(String reviewId) {
        String sql = "SELECT ciphertext, aad FROM context_blob WHERE review_id = ? "
                + "ORDER BY created_at DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return encryption.decrypt(rs.getBytes("ciphertext"), rs.getString("aad"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("context_blob read-by-review failed", e);
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-review-worker:test --tests '*PostgresBlobStoreByReviewTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add spire-review-worker/src/main/java/dev/codespire/worker/adapters/PostgresBlobStore.java \
        spire-review-worker/src/test/java/dev/codespire/worker/adapters/PostgresBlobStoreByReviewTest.java
git commit -m "Look a context blob up by the review that owns it"
```

---

### Task 2: Serve a review's assembled context from the worker

**Files:**
- Modify: `spire-review-worker/build.gradle.kts` (module file — NOT the root `build.gradle.kts`)
- Create: `spire-review-worker/src/main/java/dev/codespire/worker/web/ReviewContextResource.java`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/web/ReviewContextResourceTest.java`

**Interfaces:**
- Consumes: `PostgresBlobStore.getByReview(String)` from Task 1; `ReviewIds.reviewId(RepoRef, long)`; `AssembledContext(String contextId, List<ContextItem> items, Set<String> contributingSources, Set<String> missingSources)`; `ContextItem(String kind, String title, String body, String uri)`.
- Produces: `GET /api/review-context/{workspace}/{slug}/{pr}` returning JSON `{ items: ContextItem[], contributingSources: string[], missingSources: string[] }`. Always 200; a review with no blob returns empty arrays.

The path mirrors the orchestrator's existing `/api/reviews/{workspace}/{slug}/{pr}` so the UI passes the three values it already holds. A reviewId cannot be a single path segment — it contains `/` and `#`.

- [ ] **Step 1: Add the REST extension**

The worker has no REST extension today (only `quarkus-jackson`, `quarkus-messaging-kafka`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-config-yaml`, `quarkus-smallrye-health`, `quarkus-logging-json`). Its HTTP port already serves `/q/health`.

In `spire-review-worker/build.gradle.kts`, add below `implementation("io.quarkus:quarkus-jackson")`:

```kotlin
    implementation("io.quarkus:quarkus-rest-jackson") // read-only context endpoint for the review detail page
```

and below `testImplementation("io.quarkus:quarkus-junit5")`:

```kotlin
    testImplementation("io.rest-assured:rest-assured")
```

- [ ] **Step 2: Write the failing test**

Create `spire-review-worker/src/test/java/dev/codespire/worker/web/ReviewContextResourceTest.java`:

```java
package dev.codespire.worker.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.worker.adapters.PostgresBlobStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * The review detail page's Context section reads what the model was actually given, so this serves
 * the stored blob rather than re-resolving references against a live host.
 */
@QuarkusTest
class ReviewContextResourceTest {

    @Inject
    PostgresBlobStore store;

    @Inject
    ObjectMapper mapper;

    private void store(String reviewId, AssembledContext context) throws Exception {
        store.put(BlobStore.Kind.CONTEXT, reviewId, mapper.writeValueAsBytes(context));
    }

    @Test
    void returnsTheItemsTheReviewWasGiven() throws Exception {
        String reviewId = "review::acme/widgets#801";
        store(reviewId, new AssembledContext(null,
                List.of(new ContextItem("ISSUE", "acme/widgets#7 Cap discounts at 50%",
                        "State: open\n\nMust reject above 50.", "https://example.invalid/issues/7")),
                Set.of("github-issues"), Set.of()));

        when().get("/api/review-context/acme/widgets/801")
                .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].kind", is("ISSUE"))
                .body("items[0].title", is("acme/widgets#7 Cap discounts at 50%"))
                .body("contributingSources", hasItem("github-issues"));

        store.deleteByReview(reviewId);
    }

    /** No blob is the normal path when nothing was referenced — an empty result, never an error. */
    @Test
    void returnsAnEmptyResultWhenTheReviewHasNoContext() {
        when().get("/api/review-context/acme/widgets/802")
                .then().statusCode(200)
                .body("items", hasSize(0))
                .body("contributingSources", hasSize(0));
    }

    @Test
    void reportsSourcesThatWereExpectedButContributedNothing() throws Exception {
        String reviewId = "review::acme/widgets#803";
        store(reviewId, new AssembledContext(null, List.of(),
                Set.of(), Set.of("jira")));

        when().get("/api/review-context/acme/widgets/803")
                .then().statusCode(200)
                .body("missingSources", hasItem("jira"));

        store.deleteByReview(reviewId);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :spire-review-worker:test --tests '*ReviewContextResourceTest*'`
Expected: FAIL — 404 on every request, because no resource is registered.

- [ ] **Step 4: Write minimal implementation**

Create `spire-review-worker/src/main/java/dev/codespire/worker/web/ReviewContextResource.java`:

```java
package dev.codespire.worker.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worker.adapters.PostgresBlobStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * The assembled context a review was given, for the review detail page.
 *
 * <p>The worker serves this rather than the orchestrator reading across schemas: the blob is this
 * service's data, encrypted with its keyset under its own AAD convention, and only this service
 * can address it (a review's contextRef is never projected). Same shape as the attention panel,
 * where each service answers for its own schema and the UI merges.
 *
 * <p>Read-only and idempotent — nothing here writes, deletes or re-resolves anything.
 */
@Path("/api/review-context")
@Produces(MediaType.APPLICATION_JSON)
public class ReviewContextResource {

    private static final Logger LOG = Logger.getLogger(ReviewContextResource.class);
    private static final ReviewContextView EMPTY = new ReviewContextView(List.of(), Set.of(), Set.of());

    @Inject
    PostgresBlobStore blobStore;

    @Inject
    ObjectMapper mapper;

    /** Read-only projection of {@link AssembledContext} — no contextId, which is internal. */
    public record ReviewContextView(List<ContextItem> items,
                                    Set<String> contributingSources,
                                    Set<String> missingSources) {
    }

    @GET
    @Path("/{workspace}/{slug}/{pr}")
    public ReviewContextView get(@PathParam("workspace") String workspace,
                                 @PathParam("slug") String slug,
                                 @PathParam("pr") long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        byte[] payload = blobStore.getByReview(reviewId);
        if (payload == null) {
            return EMPTY;
        }
        return view(payload, reviewId);
    }

    private ReviewContextView view(byte[] payload, String reviewId) {
        try {
            AssembledContext context = mapper.readValue(payload, AssembledContext.class);
            return new ReviewContextView(
                    context.items() == null ? List.of() : context.items(),
                    context.contributingSources() == null ? Set.of() : context.contributingSources(),
                    context.missingSources() == null ? Set.of() : context.missingSources());
        } catch (IOException e) {
            // A blob we cannot parse is a display problem, not a reason to fail the page. Log the
            // review id only: context items quote issue and ticket text, so the payload never goes
            // to a log.
            LOG.warnf(e, "Unreadable context blob for %s", reviewId);
            return EMPTY;
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :spire-review-worker:test --tests '*ReviewContextResourceTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Run the whole worker module**

Run: `./gradlew :spire-review-worker:test`
Expected: BUILD SUCCESSFUL — adding a REST extension must not disturb the Kafka pipeline tests.

- [ ] **Step 7: Commit**

```bash
git add spire-review-worker/build.gradle.kts \
        spire-review-worker/src/main/java/dev/codespire/worker/web/ReviewContextResource.java \
        spire-review-worker/src/test/java/dev/codespire/worker/web/ReviewContextResourceTest.java
git commit -m "Serve a review's assembled context from the worker"
```

---

### Task 3: Fetch a pull request's description on demand

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/web/ReviewsResource.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/web/ReviewDescriptionResourceTest.java` (create)

**Interfaces:**
- Consumes: `ReviewProviderResolver.resolveForReview(String reviewId) -> Optional<ScmProvider>`; `ProviderClients.diffSource(ScmProvider) -> DiffSource`; `DiffSource.fetchPullRequest(RepoRef, long) -> PullRequest`; `PullRequest.description()`; `ScmApiException.isNotFound()` / `.isUnauthorized()`; `ReviewIds.reviewId(RepoRef, long)`.
- Produces: `GET /api/reviews/{workspace}/{slug}/{pr}/description` returning `{ description: string|null }`.

Live rather than stored: the description is on neither `review_status` nor `DiffFetched`, and persisting it would mean a migration plus a nullable field on a wire-format event. The UI labels this as the PR's current text, never as what the review parsed.

- [ ] **Step 1: Write the failing test**

Create `spire-orchestrator/src/test/java/dev/codespire/orchestrator/web/ReviewDescriptionResourceTest.java`. Model the WireMock and provider-registration setup on `ContextProviderResourceTest`, which already stands up a WireMock server and registers a provider through the REST API:

```java
package dev.codespire.orchestrator.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;

/**
 * The description is fetched live, so its failures are the SCM's. Each must surface as itself: an
 * empty description would read as "this pull request has no description", which is a different fact.
 */
@QuarkusTest
class ReviewDescriptionResourceTest {

    @Test
    void returnsTheDescriptionOfAKnownPullRequest() {
        // Register a provider + stub the SCM's PR endpoint to return a description, then:
        when().get("/api/reviews/acme/widgets/1/description")
                .then().statusCode(200)
                .body("description", containsString("Implements"));
    }

    @Test
    void reportsNotFoundWhenTheScmDoesNotKnowThePullRequest() {
        when().get("/api/reviews/acme/widgets/99999/description")
                .then().statusCode(404);
    }

    /**
     * A workspace with no enabled provider must say so. Returning an empty description would read
     * as "this pull request has no description", which is a different fact entirely.
     */
    @Test
    void reportsNoProviderRatherThanAnEmptyDescription() {
        when().get("/api/reviews/unregistered/repo/1/description")
                .then().statusCode(404)
                .body(containsString("No enabled provider"));
    }
}
```

Fill the first test's setup by copying the WireMock + provider-registration pattern from `ContextProviderResourceTest` (`@BeforeAll` server on a dynamic port, provider registered via `POST /api/providers`, stub returning the provider's PR JSON).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*ReviewDescriptionResourceTest*'`
Expected: FAIL — 404 on the description path, because the sub-resource does not exist.

- [ ] **Step 3: Write minimal implementation**

Add to `ReviewsResource` (inject `ReviewProviderResolver reviews;` and `ProviderClients clients;` if not already present):

```java
    /** The pull request's description as it stands NOW — fetched live, never stored. */
    public record DescriptionView(String description) {
    }

    @GET
    @Path("/{workspace}/{slug}/{pr}/description")
    public DescriptionView description(@PathParam("workspace") String workspace,
                                       @PathParam("slug") String slug,
                                       @PathParam("pr") long pr) {
        RepoRef repo = new RepoRef(workspace, slug);
        ScmProvider provider = reviews.resolveForReview(ReviewIds.reviewId(repo, pr))
                .orElseThrow(() -> new NotFoundException(
                        "No enabled provider for " + workspace + "/" + slug));
        try {
            return new DescriptionView(clients.diffSource(provider).fetchPullRequest(repo, pr).description());
        } catch (RuntimeException e) {
            // Provider-neutral: every adapter implements ScmApiException, so one platform's status
            // codes are never interpreted here. A genuine bug still surfaces unchanged.
            if (!(e instanceof ScmApiException api)) {
                throw e;
            }
            if (api.isNotFound()) {
                throw new NotFoundException("Pull request not found: " + workspace + "/" + slug + "#" + pr);
            }
            throw new ServiceUnavailableException(
                    api.isUnauthorized() ? "The stored credential was rejected." : "Could not reach the provider.");
        }
    }
```

Add the imports the file does not already have: `dev.codespire.contract.event.ReviewIds`, `dev.codespire.contract.scm.RepoRef`, `dev.codespire.contract.port.ScmApiException`, `dev.codespire.orchestrator.provider.ReviewProviderResolver`, `dev.codespire.orchestrator.provider.ProviderClients`, `jakarta.ws.rs.NotFoundException`, `jakarta.ws.rs.ServiceUnavailableException`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests '*ReviewDescriptionResourceTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/web/ReviewsResource.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/web/ReviewDescriptionResourceTest.java
git commit -m "Fetch a pull request description for the detail page"
```

---

### Task 4: Guard the comments marker the UI splits on

**Files:**
- Modify: `spire-context-github/src/test/java/dev/codespire/context/github/GitHubIssueContextProviderTest.java`
- Modify: `spire-context-gitlab/src/test/java/dev/codespire/context/gitlab/GitLabIssueContextProviderTest.java`
- Modify: `spire-context-jira/src/test/java/dev/codespire/context/jira/JiraContextProviderTest.java`

**Interfaces:**
- Consumes: each provider's existing WireMock test setup.
- Produces: nothing consumed by later tasks. This is the guard that makes Task 6's split safe.

All three providers append the literal `"\n\nRecent comments:"` into `ContextItem.body`. The UI splits on it. The string lives in three separate Apache-2.0 modules and is consumed across a language boundary, so no shared constant is possible — a test per provider is the only thing that can make a change to it fail loudly instead of silently dropping the comments section.

- [ ] **Step 1: Write the failing test in each provider module**

Add to each of the three test classes, adapting the existing stub setup in that file so the fetched object carries at least one comment (GitHub `/comments`, GitLab `/notes`, Jira `/comment`):

```java
    /**
     * The review detail page splits ContextItem.body on this exact marker to show comments
     * collapsed. It is a cross-module, cross-language contract with no shared constant, so
     * changing the wording here must fail a test rather than silently empty that section.
     */
    @Test
    void rendersCommentsUnderTheMarkerTheReviewDetailPageSplitsOn() {
        // ... existing stub setup for an object WITH comments ...
        ContextContribution contribution = provider.contribute(request).toCompletableFuture().join();

        assertEquals(1, contribution.items().size());
        assertTrue(contribution.items().get(0).body().contains("\n\nRecent comments:"),
                "body must carry the 'Recent comments:' marker the UI splits on");
    }
```

- [ ] **Step 2: Run the tests to verify they pass immediately**

Run: `./gradlew :spire-context-github:test :spire-context-gitlab:test :spire-context-jira:test`
Expected: PASS. These are characterisation tests — they pin behaviour that already exists rather than driving new behaviour, so passing first time is correct.

- [ ] **Step 3: Verify each test discriminates**

For one provider, temporarily change its marker in the main source from `"\n\nRecent comments:"` to `"\n\nComments:"` and re-run that module's tests.
Expected: FAIL. Restore the marker and re-run.
Expected: PASS.

A characterisation test that cannot fail is decoration. Do not skip this step.

- [ ] **Step 4: Commit**

```bash
git add spire-context-github/src/test spire-context-gitlab/src/test spire-context-jira/src/test
git commit -m "Pin the comments marker the review detail page splits on"
```

---

### Task 5: Reach the worker from the UI

**Files:**
- Modify: `spire-ui/vite.config.ts`
- Modify: `spire-ui/src/api.ts`
- Test: `spire-ui/src/api.contextsection.test.ts` (create)

**Interfaces:**
- Consumes: Task 2's `GET /api/review-context/{workspace}/{slug}/{pr}`; Task 3's `GET /api/reviews/{workspace}/{slug}/{pr}/description`.
- Produces:
  - `interface ContextItem { kind: string; title: string; body: string; uri: string | null }`
  - `interface ReviewContext { items: ContextItem[]; contributingSources: string[]; missingSources: string[] }`
  - `fetchReviewContext(workspace: string, slug: string, pr: number): Promise<ReviewContext>`
  - `fetchPrDescription(workspace: string, slug: string, pr: number): Promise<string | null>`

- [ ] **Step 1: Add the proxy route**

In `spire-ui/vite.config.ts`, add ABOVE the catch-all `'/api'` entry — order matters, the first match wins, exactly as the `/api/webhook-repos` comment already explains:

```ts
      // The assembled context is the WORKER's data (:34082) — it owns the blob and is the only
      // service that can address it. More specific than /api, so it must be listed first.
      '/api/review-context': { target: worker, changeOrigin: true },
```

Define `worker` beside the existing `gateway` and `orchestrator` constants at the top of the file, following their exact pattern (read the file to match how each reads its port from the environment).

- [ ] **Step 2: Write the failing test**

Create `spire-ui/src/api.contextsection.test.ts`:

```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchReviewContext, fetchPrDescription } from './api';

afterEach(() => vi.unstubAllGlobals());

const ok = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body } as Response);

describe('context section api', () => {
  it('reads a review context from the worker route', async () => {
    const fetchMock = ok({ items: [], contributingSources: [], missingSources: [] });
    vi.stubGlobal('fetch', fetchMock);

    await fetchReviewContext('acme', 'widgets', 7);

    expect(fetchMock).toHaveBeenCalledWith('/api/review-context/acme/widgets/7');
  });

  it('reads a description from the orchestrator route', async () => {
    const fetchMock = ok({ description: 'Implements #7' });
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchPrDescription('acme', 'widgets', 7)).toBe('Implements #7');
    expect(fetchMock).toHaveBeenCalledWith('/api/reviews/acme/widgets/7/description');
  });

  /** A repo or branch name can contain characters that change a URL's meaning if unescaped. */
  it('encodes path segments', async () => {
    const fetchMock = ok({ items: [], contributingSources: [], missingSources: [] });
    vi.stubGlobal('fetch', fetchMock);

    await fetchReviewContext('acme corp', 'wid/gets', 7);

    expect(fetchMock).toHaveBeenCalledWith('/api/review-context/acme%20corp/wid%2Fgets/7');
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/api.contextsection.test.ts`
Expected: FAIL — `fetchReviewContext is not a function`.

- [ ] **Step 4: Write minimal implementation**

Add to `spire-ui/src/api.ts`, following the file's existing fetch-and-check idiom (read a neighbouring function first and match its error handling):

```ts
export interface ContextItem {
  kind: string;
  title: string;
  body: string;
  uri: string | null;
}

export interface ReviewContext {
  items: ContextItem[];
  contributingSources: string[];
  missingSources: string[];
}

const seg = (s: string) => encodeURIComponent(s);

/** What the model was given for this review — read from the stored blob, never re-resolved. */
export async function fetchReviewContext(
  workspace: string,
  slug: string,
  pr: number,
): Promise<ReviewContext> {
  const res = await fetch(`/api/review-context/${seg(workspace)}/${seg(slug)}/${pr}`);
  if (!res.ok) throw new Error(`Could not load context (${res.status})`);
  return res.json();
}

/** The pull request's description as it stands NOW — fetched live, so it may have been edited. */
export async function fetchPrDescription(
  workspace: string,
  slug: string,
  pr: number,
): Promise<string | null> {
  const res = await fetch(`/api/reviews/${seg(workspace)}/${seg(slug)}/${pr}/description`);
  if (!res.ok) throw new Error(`Could not load the description (${res.status})`);
  const body = await res.json();
  return body.description ?? null;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run src/api.contextsection.test.ts`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add spire-ui/vite.config.ts spire-ui/src/api.ts spire-ui/src/api.contextsection.test.ts
git commit -m "Read review context and PR description from the UI"
```

---

### Task 6: The Context card

**Files:**
- Create: `spire-ui/src/components/ContextCard.tsx`
- Test: `spire-ui/src/components/ContextCard.test.tsx`

**Interfaces:**
- Consumes: `fetchReviewContext`, `fetchPrDescription`, `ContextItem`, `ReviewContext` from Task 5.
- Produces: `export default function ContextCard({ workspace, slug, pr }: { workspace: string; slug: string; pr: number })`.
- Produces: `export function splitComments(body: string): { detail: string; comments: string | null }`.

`render.tsx` is 904 lines, far past the 250-line component guideline, so this goes in its own file rather than growing it further.

- [ ] **Step 1: Write the failing test**

Create `spire-ui/src/components/ContextCard.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ContextCard, { splitComments } from './ContextCard';
import * as api from '../api';

const item = {
  kind: 'ISSUE',
  title: 'acme/widgets#7 Cap discounts at 50%',
  body: 'State: open\n\nMust reject above 50.\n\nRecent comments:\n- alice: agreed',
  uri: 'https://example.invalid/issues/7',
};

describe('splitComments', () => {
  it('separates the comment block from the detail', () => {
    expect(splitComments(item.body)).toEqual({
      detail: 'State: open\n\nMust reject above 50.',
      comments: '- alice: agreed',
    });
  });

  it('returns no comments when the marker is absent', () => {
    expect(splitComments('State: open\n\nJust a body.')).toEqual({
      detail: 'State: open\n\nJust a body.',
      comments: null,
    });
  });
});

describe('ContextCard', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchPrDescription').mockResolvedValue('Implements #7');
  });

  it('shows each item with its body and comments collapsed', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: ['github-issues'],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    expect(await screen.findByText(/Cap discounts at 50%/)).toBeInTheDocument();
    expect(screen.queryByText(/Must reject above 50/)).not.toBeInTheDocument();
    expect(screen.queryByText(/alice: agreed/)).not.toBeInTheDocument();
  });

  it('reveals the comments only when their own toggle is used', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    fireEvent.click(await screen.findByRole('button', { name: /Cap discounts at 50%/ }));
    expect(await screen.findByText(/Must reject above 50/)).toBeInTheDocument();
    expect(screen.queryByText(/alice: agreed/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /1 comment/i }));
    expect(await screen.findByText(/alice: agreed/)).toBeInTheDocument();
  });

  it('renders no comments toggle when the item has none', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [{ ...item, body: 'State: open\n\nNo discussion.' }],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    fireEvent.click(await screen.findByRole('button', { name: /Cap discounts at 50%/ }));
    expect(screen.queryByRole('button', { name: /comment/i })).not.toBeInTheDocument();
  });

  /** No context is the normal path with no provider configured — not a failure. */
  it('explains an empty context instead of showing an error', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    expect(await screen.findByText(/No context was resolved/i)).toBeInTheDocument();
  });

  /** The description costs an SCM call, so it must not be paid for on every page load. */
  it('does not fetch the description until it is expanded', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);
    await screen.findByText(/Cap discounts at 50%/);
    expect(api.fetchPrDescription).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /description/i }));
    await waitFor(() => expect(api.fetchPrDescription).toHaveBeenCalledWith('acme', 'widgets', 7));
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/ContextCard.test.tsx`
Expected: FAIL — cannot resolve `./ContextCard`.

- [ ] **Step 3: Write minimal implementation**

Create `spire-ui/src/components/ContextCard.tsx`. Match the card markup used by `metaCard` in `render.tsx` (`div.card` > `div.head` with `span.k` and `h3`, then `div.body`) so it sits in the same visual system. Use `lucide-react` icons only — never emoji.

```tsx
import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { fetchReviewContext, fetchPrDescription, type ContextItem, type ReviewContext } from '../api';

const COMMENTS_MARKER = '\n\nRecent comments:';

/**
 * Providers append comments into the item body under a fixed marker rather than as structured
 * data, so the split happens here. A test per provider pins the marker; if one ever changes its
 * wording, that test fails rather than this quietly showing an empty comments section.
 */
export function splitComments(body: string): { detail: string; comments: string | null } {
  const at = body.indexOf(COMMENTS_MARKER);
  if (at < 0) return { detail: body, comments: null };
  return {
    detail: body.slice(0, at).trim(),
    comments: body.slice(at + COMMENTS_MARKER.length).trim(),
  };
}

function commentCount(comments: string): number {
  return comments.split('\n').filter((line) => line.startsWith('- ')).length;
}

function Item({ item }: { item: ContextItem }) {
  const [open, setOpen] = useState(false);
  const [showComments, setShowComments] = useState(false);
  const { detail, comments } = splitComments(item.body);

  return (
    <div className="ctx-item">
      <button className="ctx-item-head" onClick={() => setOpen(!open)}>
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span className="badge">{item.kind}</span>
        <span className="ctx-item-title">{item.title}</span>
      </button>
      {open && (
        <div className="ctx-item-body">
          <pre className="ctx-detail">{detail}</pre>
          {comments && (
            <>
              <button className="ctx-comments-toggle" onClick={() => setShowComments(!showComments)}>
                {showComments ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                {commentCount(comments)} comments
              </button>
              {showComments && <pre className="ctx-comments">{comments}</pre>}
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default function ContextCard({
  workspace,
  slug,
  pr,
}: {
  workspace: string;
  slug: string;
  pr: number;
}) {
  const [context, setContext] = useState<ReviewContext | null>(null);
  const [descriptionOpen, setDescriptionOpen] = useState(false);
  const [description, setDescription] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchReviewContext(workspace, slug, pr)
      .then((c) => {
        if (!cancelled) setContext(c);
      })
      .catch(() => {
        if (!cancelled) setContext({ items: [], contributingSources: [], missingSources: [] });
      });
    return () => {
      cancelled = true;
    };
  }, [workspace, slug, pr]);

  async function toggleDescription() {
    const next = !descriptionOpen;
    setDescriptionOpen(next);
    if (next && description === null) {
      setDescription(await fetchPrDescription(workspace, slug, pr).catch(() => null));
    }
  }

  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Context</h3>
        <span className="badge">as given to the model</span>
      </div>
      <div className="body">
        <button className="ctx-desc-toggle" onClick={() => void toggleDescription()}>
          {descriptionOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          Pull request description
        </button>
        {descriptionOpen && <pre className="ctx-detail">{description ?? '—'}</pre>}

        {context === null && <div className="muted">Loading…</div>}
        {context !== null && context.items.length === 0 && (
          <div className="muted">No context was resolved for this review.</div>
        )}
        {context?.items.map((item, i) => <Item key={item.uri ?? i} item={item} />)}
      </div>
    </div>
  );
}
```

Add the `ctx-*` classes to the stylesheet `render.tsx`'s cards already use — find it by searching for an existing class such as `ev-sep`, and follow the conventions there.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run src/components/ContextCard.test.tsx`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add spire-ui/src/components/ContextCard.tsx spire-ui/src/components/ContextCard.test.tsx spire-ui/src/index.css
git commit -m "Show the context a review was given"
```

---

### Task 7: Newest-first collapsible event runs

**Files:**
- Create: `spire-ui/src/components/EventStream.tsx`
- Modify: `spire-ui/src/render.tsx` (remove `eventsCard`, lines 857-903)
- Test: `spire-ui/src/components/EventStream.test.tsx`

**Interfaces:**
- Consumes: the `ReviewDetail` and `ReviewEvent` types `render.tsx` already imports.
- Produces: `export default function EventStream({ r }: { r: ReviewDetail })`, replacing `eventsCard(r)`.

- [ ] **Step 1: Write the failing test**

Create `spire-ui/src/components/EventStream.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import EventStream from './EventStream';

const ev = (type: string, at: string) => ({
  type,
  at,
  det: '',
  lane: 'result',
  ts: '2026-08-01T10:00:00Z',
  loc: null,
  threadKind: null,
  threadRef: null,
});

// Two runs, oldest first, as the API returns them.
const review = {
  events: [
    ev('ReviewRequested', '+0.0s'),
    ev('DiffFetched', '+1.2s'),
    ev('ReviewRequested', '+0.0s'),
    ev('DiffFetched', '+1.1s'),
    ev('CommentsPosted', '+9s'),
  ],
} as never;

describe('EventStream', () => {
  it('puts the newest run first and expands only it', () => {
    render(<EventStream r={review} />);

    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(2);
    expect(groups[0]).toHaveTextContent(/latest/i);
    expect(groups[0]).toHaveTextContent('CommentsPosted');
    expect(groups[1]).not.toHaveTextContent('DiffFetched');
  });

  /** A run must read in the order it executed, or cause and effect invert while diagnosing. */
  it('keeps events chronological inside a run', () => {
    render(<EventStream r={review} />);

    const text = screen.getAllByRole('group')[0].textContent ?? '';
    expect(text.indexOf('ReviewRequested')).toBeLessThan(text.indexOf('DiffFetched'));
    expect(text.indexOf('DiffFetched')).toBeLessThan(text.indexOf('CommentsPosted'));
  });

  it('expands an older run on demand', () => {
    render(<EventStream r={review} />);

    fireEvent.click(screen.getAllByRole('button')[1]);
    expect(screen.getAllByRole('group')[1]).toHaveTextContent('DiffFetched');
  });

  it('shows a single run without collapsed siblings', () => {
    render(<EventStream r={{ events: [ev('ReviewRequested', '+0.0s'), ev('DiffFetched', '+1s')] } as never} />);

    expect(screen.getAllByRole('group')).toHaveLength(1);
    expect(screen.getByRole('group')).toHaveTextContent('DiffFetched');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/EventStream.test.tsx`
Expected: FAIL — cannot resolve `./EventStream`.

- [ ] **Step 3: Write minimal implementation**

Create `spire-ui/src/components/EventStream.tsx`, moving the event row markup verbatim from `render.tsx`'s `eventsCard` (the `div.ev` block with `at-abs` / `at-rel` / `lane` / `type` / `det`) and adding grouping. Keep `formatEventTime` where it is and import it.

```tsx
import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { formatEventTime } from '../render';
import type { ReviewDetail, ReviewEvent } from '../api';

interface Run {
  label: string;
  events: ReviewEvent[];
}

/**
 * Runs are delimited by ReviewRequested. Numbering is computed in the API's chronological order so
 * a run keeps its identity once the list is reversed for display.
 */
export function toRuns(events: ReviewEvent[]): Run[] {
  const runs: Run[] = [];
  for (const e of events) {
    if (e.type === 'ReviewRequested' || runs.length === 0) {
      runs.push({ label: runs.length === 0 ? 'Initial run' : `Re-run ${runs.length}`, events: [] });
    }
    runs[runs.length - 1].events.push(e);
  }
  return runs.reverse();
}

export default function EventStream({ r }: { r: ReviewDetail }) {
  const runs = toRuns(r.events);
  const [open, setOpen] = useState<Record<number, boolean>>({ 0: true });

  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Event stream</h3>
        <span className="badge">this review only</span>
      </div>
      <div className="body">
        {runs.map((run, i) => (
          <div className="ev-run" role="group" aria-label={run.label} key={run.label}>
            <button className="ev-run-head" onClick={() => setOpen({ ...open, [i]: !open[i] })}>
              {open[i] ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              <span className="ev-sep-label">{run.label}</span>
              {i === 0 && <span className="badge">latest</span>}
              <span className="muted">{run.events.length} events</span>
            </button>
            {open[i] && (
              <div className="events">
                {run.events.map((e, j) => (
                  <div className={`ev ${e.lane}`} key={j}>
                    <div className="at">
                      <span className="at-abs">{formatEventTime(e.ts)}</span>
                      <span className="at-rel">{e.at}</span>
                    </div>
                    <div className="what">
                      <span className="lane"></span>
                      <div>
                        <div className="type">{e.type}</div>
                        <div className="det">{e.det}</div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
```

If `formatEventTime` is not currently exported from `render.tsx`, export it.

- [ ] **Step 4: Delete the old card**

Remove `eventsCard` from `render.tsx` (lines 857-903) and the now-unused `Fragment` import if nothing else uses it.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run src/components/EventStream.test.tsx`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add spire-ui/src/components/EventStream.tsx spire-ui/src/components/EventStream.test.tsx spire-ui/src/render.tsx spire-ui/src/index.css
git commit -m "Read the newest review run first"
```

---

### Task 8: Wire the detail page together

**Files:**
- Modify: `spire-ui/src/components/ReviewDetail.tsx` (the two-column grid at the end of the file)
- Test: `spire-ui/src/components/ReviewDetail.layout.test.tsx` (create)

**Interfaces:**
- Consumes: `ContextCard` (Task 6), `EventStream` (Task 7), and the existing `findingsCard`, `generalDiscussionCard`, `usageCard`, `metaCard` from `render.tsx`.
- Produces: the finished page.

- [ ] **Step 1: Write the failing test**

Create `spire-ui/src/components/ReviewDetail.layout.test.tsx`. Mock `../api` so the page renders without network, following the mocking style in `SettingsContextProviders.form.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ReviewDetail from './ReviewDetail';
import * as api from '../api';

/**
 * Model usage grows by a row per LLM call, so a re-run review pushes the fixed Metadata card off
 * the bottom of the screen. Metadata comes first.
 */
describe('ReviewDetail layout', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [],
      contributingSources: [],
      missingSources: [],
    });
    // Stub fetchReview (or the page's loader) to return a completed review with one LLM call
    // and two events — copy the fixture shape from an existing render test.
  });

  it('renders Metadata above Model usage', async () => {
    render(
      <MemoryRouter>
        <ReviewDetail />
      </MemoryRouter>,
    );

    const headings = (await screen.findAllByRole('heading', { level: 3 })).map((h) => h.textContent);
    expect(headings.indexOf('Metadata')).toBeLessThan(headings.indexOf('Model usage'));
  });

  it('renders the Context card', async () => {
    render(
      <MemoryRouter>
        <ReviewDetail />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: 'Context', level: 3 })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/ReviewDetail.layout.test.tsx`
Expected: FAIL — no `Context` heading, and `Model usage` precedes `Metadata`.

- [ ] **Step 3: Write minimal implementation**

In `ReviewDetail.tsx`, replace the closing grid with:

```tsx
      <div className="grid2" style={{ marginTop: 18 }}>
        <div>
          {findingsCard(r)}
          <ContextCard workspace={r.workspace} slug={r.slug} pr={r.pr} />
          {generalDiscussionCard(r)}
          <EventStream r={r} />
        </div>
        <div>
          {metaCard(r)}
          {usageCard(r)}
        </div>
      </div>
```

Add `import ContextCard from './ContextCard';` and `import EventStream from './EventStream';`, and drop `eventsCard` from the `render` import list.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd spire-ui && npx vitest run src/components/ReviewDetail.layout.test.tsx`
Expected: PASS, 2 tests.

- [ ] **Step 5: Run everything**

```bash
cd spire-ui && npx tsc --noEmit && npx vitest run
```
Expected: `tsc` silent; all suites pass, including the 153 that existed before.

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add spire-ui/src/components/ReviewDetail.tsx spire-ui/src/components/ReviewDetail.layout.test.tsx
git commit -m "Put metadata above model usage and show context"
```

---

### Task 9: Verify against the running stack

**Files:** none — this task changes no code.

The dev containers bake source at image build time and mount only a Gradle cache, so a running stack serves whatever was current when its image was built. A rebuild is required before any of this is visible.

- [ ] **Step 1: Rebuild the changed services**

```bash
docker compose -p spire-dev -f docker-compose.yml -f docker-compose.dev.yml \
  up -d --build orchestrator worker ui
```

Do NOT name `tunnel`. A cloudflared quick tunnel mints a new hostname whenever it re-registers, and the webhook registrations at GitHub, GitLab and Bitbucket point at the current one.

- [ ] **Step 2: Wait for readiness**

```bash
for p in 39280 39282; do
  until [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$p/q/health/ready)" = "200" ]; do sleep 5; done
done
echo ready
```

- [ ] **Step 3: Check the endpoints directly**

```bash
curl -s http://localhost:39282/api/review-context/artyomsv/spire-test/17
curl -s http://localhost:39280/api/reviews/artyomsv/spire-test/17/description
```

Review 17 resolved a GitHub issue during the 2026-07-31 pass and has a 518-byte blob, so the first must return one item. Expect the second to return the description carrying the issue reference.

- [ ] **Step 4: Check the page**

Open `http://localhost:39285/#/r/artyomsv/spire-test/17`. Confirm: Metadata sits above Model usage; the Context card lists the issue with its body collapsed and comments collapsed separately; the description expands on demand; the event stream shows the newest run first and expanded.

- [ ] **Step 5: Commit nothing**

This task has no deliverable to commit. Report what the page showed.

---

## Self-Review

**Spec coverage.** Card order → Task 8. Context items from the blob → Tasks 1, 2, 6. Live description → Tasks 3, 6. Comments split on the marker → Task 6, guarded by Task 4. Newest-first collapsible runs → Task 7. New files rather than growing `render.tsx` → Tasks 6, 7. Every test named in the spec's testing section appears in a task. No spec requirement is unassigned.

**Placeholders.** Task 3's first test and Task 8's `beforeEach` name an existing file to copy fixture setup from rather than reproducing it, because the fixture shape is long and already correct in the codebase; every other step carries its full code. No "TBD", no "handle errors appropriately", no "similar to Task N".

**Type consistency.** `ReviewContextView(items, contributingSources, missingSources)` in Task 2 matches `interface ReviewContext` in Task 5 and its use in Task 6. `getByReview(String)` is defined in Task 1 and consumed in Task 2. `splitComments` returns `{ detail, comments }` in both its test and its implementation. `ContextItem` is the existing contract record `(kind, title, body, uri)` throughout, with `uri` typed nullable on the TypeScript side because the Java record permits null.
