# Issue-reference context providers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A PR referencing a GitHub or GitLab issue pulls that issue's content into the review prompt, with the same encrypted-registry, connectivity-check and preview treatment Jira and Confluence already have.

**Architecture:** Two new framework-free Apache-2.0 adapter modules implementing the existing `ContextProvider` + `ContextReferenceSource` SPI, exactly as `spire-context-jira` does. Because `#123` is repo-relative — unlike a globally-unique Jira key — the review's SCM axis is carried on `GatherContext`/`ContextRequest` as an `ScmType` so a provider can refuse to resolve a bare reference for a review on another platform. Core gains a transported value and no branch; all five dispatch sites are already ADR-020-allowlisted composition roots.

**Tech Stack:** Java 25, JDK `HttpClient` + Jackson (no framework in the adapters), Quarkus 3.36 in the services, JUnit 6 + WireMock 3.13.2, React/Vite + vitest in `spire-ui`, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-07-30-issue-context-providers-design.md` — read it for the *why*; this plan is the *how*.

## Global Constraints

- JDK 25 toolchain (`JavaLanguageVersion.of(25)`); all Gradle commands need `JAVA_HOME="E:/Tools/jvms-2.1.0/store/jdk-25.0.3+9"`.
- **Four-space indentation in Java, two-space everywhere else.** Explicit types over `var` in Java. `interface` over `type` for TS object shapes.
- Both new modules are **Apache-2.0** (ADR-021): each carries its own `LICENSE` (copy `spire-context-jira/LICENSE` verbatim) and a row in `LICENSING.md`. **No Apache-2.0 module may depend on a service module.**
- Adapters stay **framework-free**: JDK `HttpClient` + Jackson only, no CDI annotations, no Quarkus imports.
- SSRF guard on every client: `followRedirects(NEVER)`, manual host-pinned redirects, private-address refusal on cross-host hops.
- **Never persist or log a response body on an auth failure** — a 401 body can echo the token. `*ApiException` carries status plus a fixed message.
- **No synthetic data.** Tests use WireMock with obviously-fake hosts and `TEST-`/`CANARY-` style values; the live pass uses real issues in real repositories.
- `spire-arch`'s `CoreIsProviderNeutralTest` must stay green **with no new ALLOWED entries**. If a change wants one, the design is wrong — re-read the spec.
- lucide-react icons only in the UI, never emoji.
- DTO naming: `*Dto` / `*View` / `*Payload` only.
- No new user-visible occurrences of the working name "Code Spire".
- Commit messages: imperative, ≤72-char subject, body for non-trivial changes. **Never mention AI/agentic authoring, model names, or vendor names.**

**Build commands** (from repo root, `JAVA_HOME` set as above):

```bash
./gradlew :spire-context-github:test          # one module
./gradlew :spire-arch:test                    # the neutrality check
./gradlew build                               # everything
cd spire-ui && npm test && npx tsc --noEmit   # UI
```

---

## File Structure

**New — `spire-context-github/`** (Apache-2.0 library, mirrors `spire-context-jira/`)

| File | Responsibility |
|---|---|
| `build.gradle.kts` | `java-library`, JDK 25, `api(project(":spire-contract"))`, jackson-databind, junit, wiremock |
| `LICENSE` | Apache-2.0, copied verbatim from `spire-context-jira/LICENSE` |
| `GitHubIssueRefs.java` | The grammar only: match candidates, parse one reference into a `Ref`, normalize, parse/apply the repo allow-list. Pure functions, no I/O. |
| `GitHubIssueConfig.java` | Validated config record from the brokered credential |
| `GitHubIssueApiException.java` | Non-2xx carrier with `status()` |
| `GitHubIssueClient.java` | Read-only HTTP: bearer auth, host-pinned manual redirects, JSON parse |
| `GitHubIssueContextProvider.java` | `ContextProvider` — narrows references, fetches, shapes `ContextItem`s |
| `GitHubIssueReferenceSource.java` | `ContextReferenceSource` — credential-free extraction, delegates to `GitHubIssueRefs` |

**New — `spire-context-gitlab/`** — the same eight files with `GitLab` names, plus merge-request and epic resolution in the provider and `!`/`&` in the grammar.

**Modified**

| File | Change |
|---|---|
| `spire-contract/.../port/ScmType.java` | add `fromProviderType(String)` |
| `spire-contract/.../review/ContextRequest.java` | add `ScmType scmType` |
| `spire-contract/.../command/ActionCommand.java` | `GatherContext` gains `ScmType scmType` |
| `spire-contract/.../review/ContextItem.java` | javadoc kind list gains `ISSUE`, `PULL_REQUEST`, `EPIC` |
| `spire-orchestrator/.../pipeline/ResultSaga.java` | set `scmType` when building `GatherContext` |
| `spire-orchestrator/.../context/ContextProviderResource.java` | `TYPES` + two preview branches |
| `spire-orchestrator/.../context/ContextKeyValidator.java` | two check paths |
| `spire-orchestrator/build.gradle.kts` | depend on both new modules |
| `spire-review-worker/.../adapters/WorkerContextClients.java` | two `case` arms |
| `spire-review-worker/.../adapters/WorkerContextReferences.java` | two extractors |
| `spire-review-worker/.../pipeline/ContextWorker.java` | thread `command.scmType()` into each request |
| `spire-review-worker/build.gradle.kts` | depend on both new modules |
| `settings.gradle.kts` | include the three new modules |
| `LICENSING.md` | three Apache-2.0 rows |
| `spire-context-jira/.../JiraClient.java`, `spire-context-confluence/.../ConfluenceClient.java` | migrated onto the shared client (Task 3) |
| `spire-context-jira/build.gradle.kts`, `spire-context-confluence/build.gradle.kts` | depend on `spire-http` |
| `spire-ui/src/api.ts` | `ContextType` union gains both |
| `spire-ui/src/components/SettingsContextProviders.tsx` | `CONTEXT_TYPES` + `TYPE_COPY` entries |
| `docs/SMOKE-TEST.md`, `docs/ROADMAP.md`, `CLAUDE.md` | runbook + status |

**New — `spire-http/`** (Apache-2.0 library, depends on nothing else in the repo)

| File | Responsibility |
|---|---|
| `build.gradle.kts` | `java-library`, JDK 25, jackson-databind only |
| `LICENSE` | Apache-2.0, copied from `spire-context-jira/LICENSE` |
| `HttpFailures.java` | Functional interface that builds the calling adapter's own exception |
| `PinnedJsonConfig.java` | apiName, baseUrl, finished Authorization value, extra headers, sign-in hint |
| `PinnedJsonClient.java` | The transport: manual host-pinned redirects, private-address refusal, non-JSON-2xx detection, JSON parse |

**Why two adapter modules but one HTTP client.** The adapters are separate because their APIs, JSON shapes and reference grammars genuinely differ — one module would be a class branching on provider, which is what ADR-020 exists to prevent, merely relocated outside core.

The transport is the opposite case. `JiraClient` and `ConfluenceClient` are **byte-identical** apart from javadoc and one word in a hint; copying it twice more would leave four homes for one SSRF guard, so a security fix to it would have to land in four places with nothing failing if it landed in three. Task 3 extracts it into `spire-http` and migrates both existing adapters onto it before either new client is written. Each adapter keeps its own exception type — that is what `HttpFailures` is for — so callers still catch narrowly and each type keeps its own policy on whether a response body may appear in a message.

---

## Task 1: Carry the review's SCM axis to the context pipeline

Nothing user-visible; every later task depends on it. A repo-relative reference is unresolvable without knowing which platform the review runs on, and the pipeline currently cannot say.

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/port/ScmType.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/review/ContextRequest.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/ActionCommand.java:77-80`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:115-125`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ContextWorker.java:203-207`
- Test: `spire-contract/src/test/java/dev/codespire/contract/port/ScmTypeTest.java` (create)

**Interfaces:**
- Produces: `ScmType.fromProviderType(String) -> Optional<ScmType>`; `ContextRequest.scmType() -> ScmType` (nullable); `GatherContext.scmType() -> ScmType` (nullable). Every later task reads `request.scmType()`.

- [ ] **Step 1: Write the failing test**

Create `spire-contract/src/test/java/dev/codespire/contract/port/ScmTypeTest.java`:

```java
package dev.codespire.contract.port;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry stores a provider type as a string; the pipeline compares an enum. This lookup is the
 * only bridge, so an unknown or absent string must resolve to empty rather than to a wrong platform —
 * a repo-relative reference resolved against the wrong host fetches a real but unrelated issue.
 */
class ScmTypeTest {

    @Test
    void resolvesEveryDeclaredProviderTypeString() {
        for (ScmType type : ScmType.values()) {
            assertEquals(Optional.of(type), ScmType.fromProviderType(type.providerType()));
        }
    }

    @Test
    void resolvesNothingForAnUnknownOrAbsentString() {
        assertEquals(Optional.empty(), ScmType.fromProviderType("not-a-provider"));
        assertEquals(Optional.empty(), ScmType.fromProviderType(null));
        assertEquals(Optional.empty(), ScmType.fromProviderType(""));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :spire-contract:test --tests '*ScmTypeTest*'
```

Expected: compile failure — `cannot find symbol: method fromProviderType(String)`.

- [ ] **Step 3: Add the lookup**

In `ScmType.java`, after `providerType()`:

```java
    /**
     * The type whose {@link #providerType()} equals {@code providerType}, or empty when the string
     * names nothing we support. Empty is deliberately not a default: a caller that cannot identify
     * the platform must decline to act rather than assume one.
     */
    public static java.util.Optional<ScmType> fromProviderType(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return java.util.Optional.empty();
        }
        for (ScmType type : values()) {
            if (type.providerType.equals(providerType)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }
```

- [ ] **Step 4: Run it and confirm it passes**

```bash
./gradlew :spire-contract:test --tests '*ScmTypeTest*'
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Add the field to `ContextRequest`**

Replace the record header and add the accessor doc. The full file becomes:

```java
package dev.codespire.contract.review;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;

import java.util.Set;

/**
 * What the context aggregator asks every provider to resolve.
 *
 * <p>{@code references} is one neutral, recall-favouring set of candidates found in the PR's own
 * text — issue keys, page links, whatever a registered
 * {@link dev.codespire.contract.port.ContextReferenceSource} recognises. Each provider narrows it to
 * the entries it can actually resolve, so nothing outside a provider needs to know which syntax
 * belongs to which source.
 *
 * <p>{@code scmType} is the platform this review runs on. Some references are repo-relative — an
 * issue number means nothing without a repository AND a host, and the same {@code workspace/slug}
 * routinely exists on two platforms. A provider compares this against its own axis before resolving
 * such a reference against {@code repo}; core only carries the value. Null means the platform could
 * not be determined, in which case a provider needing it must decline.
 */
public record ContextRequest(String reviewId,
                             RepoRef repo,
                             long prId,
                             String commit,
                             Set<String> references,
                             Set<String> expectedSources,
                             ScmType scmType) {

    public ContextRequest {
        references = references == null ? null : Set.copyOf(references);
        expectedSources = expectedSources == null ? null : Set.copyOf(expectedSources);
    }
}
```

- [ ] **Step 6: Add the field to `GatherContext`**

In `ActionCommand.java`, replace the `GatherContext` record (currently lines 77-80). Add `import dev.codespire.contract.port.ScmType;` to the file's imports if absent:

```java
    /**
     * {@code scmType} is the platform the review runs on, so a context provider can tell whether a
     * repo-relative reference (an issue number) belongs to its own host. Null when the review's
     * provider could not be resolved; a provider that needs it then contributes nothing.
     */
    record GatherContext(String reviewId, RepoRef repo, long prId, String commit,
                         Set<String> references,
                         String contextCredential,
                         ScmType scmType) implements ActionCommand {
    }
```

- [ ] **Step 7: Set it in `ResultSaga`**

`ResultSaga` already injects `ReviewProviderResolver providers` (line 68) — the shared path introduced by the 2026-07-25 cross-provider fix. Replace the `GatherContext` emit inside the `DiffFetched` case:

```java
                String workspace = ReviewIds.parse(e.reviewId()).repo().workspace();
                String contextCred = workerContextCredentials.packAll(workspace).orElse(null);
                // Which platform this review runs on, from the same resolver the credential path and
                // the conversation saga use — one answer to "which SCM is this review on". Null when
                // unresolvable, which makes repo-relative context providers decline rather than guess.
                ScmType scmType = providers.resolveForReview(e.reviewId())
                        .flatMap(p -> ScmType.fromProviderType(p.type()))
                        .orElse(null);
                commands.emit(new ActionCommand.GatherContext(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(),
                        e.references() == null ? Set.of() : e.references(), contextCred, scmType));
```

Add `import dev.codespire.contract.port.ScmType;` to `ResultSaga`.

- [ ] **Step 8: Thread it in `ContextWorker`**

One place — the private factory at line 203. Replace it with:

```java
    private static ContextRequest request(GatherContext command, Set<String> references,
                                          Set<String> expected) {
        return new ContextRequest(command.reviewId(), command.repo(), command.prId(), command.commit(),
                references, expected, command.scmType());
    }
```

- [ ] **Step 9: Fix every remaining construction site**

Compile and let the compiler enumerate them:

```bash
./gradlew build -x test 2>&1 | grep -E "error|ContextRequest|GatherContext"
```

For each site, pass the honest value:
- `ContextProviderResource` previews — pass the provider's own axis (Task 10 covers this; for now pass `null` so it compiles, and Task 10 replaces it).
- Existing tests constructing `ContextRequest`/`GatherContext` — append `null` where the test does not care about the platform. Do **not** delete or weaken an assertion to make it compile.

- [ ] **Step 10: Run the full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. Confirm the run was real, not cached:

```bash
find . -name "TEST-*.xml" -newermt "-10 minutes" | wc -l
```

Expected: a non-zero count (Gradle reports success from cache in seconds without running anything).

- [ ] **Step 11: Confirm the neutrality check still passes**

```bash
./gradlew :spire-arch:test
```

Expected: PASS. `ScmType` is already allowlisted; `ContextRequest` and `GatherContext` name no provider.

- [ ] **Step 12: Commit**

```bash
git add spire-contract spire-orchestrator spire-review-worker
git commit -m "Carry the review's SCM platform to the context pipeline"
```

Body: an issue number is repo-relative, the same workspace/slug exists on more than one platform, so a provider must be able to tell whether a bare reference belongs to its host; resolved from the shared ReviewProviderResolver and failing closed when unknown.

---

## Task 2: GitHub reference grammar

Pure functions, no I/O — independently reviewable, and everything else in the GitHub half depends on the shapes it defines.

**Files:**
- Create: `spire-context-github/build.gradle.kts`
- Create: `spire-context-github/LICENSE` (copy of `spire-context-jira/LICENSE`)
- Create: `spire-context-github/src/main/java/dev/codespire/context/github/GitHubIssueRefs.java`
- Modify: `settings.gradle.kts`
- Modify: `LICENSING.md:22-23`
- Test: `spire-context-github/src/test/java/dev/codespire/context/github/GitHubIssueRefsTest.java`

**Interfaces:**
- Produces:
  - `record GitHubIssueRefs.Ref(String owner, String repo, int number)` with `boolean isRepoRelative()` (true when `owner == null`)
  - `static Set<String> candidates(String... texts)`
  - `static Optional<Ref> parse(String reference)`
  - `static String normalize(String reference)`
  - `static Set<String> parseRepoAllowList(String raw)`
  - `static boolean allows(Set<String> allowList, String owner, String repo)`

- [ ] **Step 1: Scaffold the module**

Create `spire-context-github/build.gradle.kts`:

```kotlin
// GitHub issue context provider: resolves the issue, pull-request and cross-repo
// references in a PR's text into ContextItems for the review prompt (CONTRACT §7/§8).
// Framework-free library (JDK HttpClient + Jackson); CDI wiring happens in the
// service that hosts it (spire-review-worker).
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":spire-contract"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
```

```bash
cp spire-context-jira/LICENSE spire-context-github/LICENSE
```

In `settings.gradle.kts`, after `include("spire-context-confluence")`:

```kotlin
include("spire-context-github")
```

In `LICENSING.md`, after the `spire-context-confluence` row:

```markdown
| `spire-context-github` | Apache-2.0 | Same. |
```

- [ ] **Step 2: Write the failing test**

Create `spire-context-github/src/test/java/dev/codespire/context/github/GitHubIssueRefsTest.java`:

```java
package dev.codespire.context.github;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar, with no network. Extraction favours recall — a false candidate costs one 404 that the
 * provider skips — so these tests pin the boundaries that matter: what must NOT match (so real prose
 * does not turn into a fetch storm), and what must parse into which shape (so a repo-relative
 * reference is distinguishable from one that names its own repository).
 */
class GitHubIssueRefsTest {

    @Test
    void findsABareReferenceAfterWhitespaceOrPunctuation() {
        assertEquals(Set.of("#123"), GitHubIssueRefs.candidates("fixes #123"));
        assertEquals(Set.of("#7"), GitHubIssueRefs.candidates("(#7)"));
    }

    /** A '#' glued to a word is a fragment or an anchor, not an issue. */
    @Test
    void ignoresAHashInsideAWord() {
        assertTrue(GitHubIssueRefs.candidates("abc#1").isEmpty());
        assertTrue(GitHubIssueRefs.candidates("http://x/y#3").isEmpty());
    }

    /** The qualified form must win outright: it must not also yield a bare '#123'. */
    @Test
    void findsAQualifiedReferenceWithoutAlsoYieldingTheBareForm() {
        assertEquals(Set.of("acme/widgets#123"), GitHubIssueRefs.candidates("see acme/widgets#123"));
    }

    @Test
    void findsIssueAndPullRequestUrlsIncludingOnAnEnterpriseHost() {
        assertEquals(Set.of("https://github.com/acme/widgets/issues/12"),
                GitHubIssueRefs.candidates("https://github.com/acme/widgets/issues/12"));
        assertEquals(Set.of("https://ghe.example.invalid/acme/widgets/pull/34"),
                GitHubIssueRefs.candidates("https://ghe.example.invalid/acme/widgets/pull/34"));
    }

    @Test
    void capsCandidatesSoALinkFarmCannotDriveAFetchStorm() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            text.append(" #").append(i);
        }
        assertEquals(10, GitHubIssueRefs.candidates(text.toString()).size());
    }

    @Test
    void parsesABareReferenceAsRepoRelative() {
        GitHubIssueRefs.Ref ref = GitHubIssueRefs.parse("#123").orElseThrow();
        assertTrue(ref.isRepoRelative());
        assertEquals(123, ref.number());
    }

    @Test
    void parsesAQualifiedReferenceAndAUrlIntoTheirOwnRepository() {
        GitHubIssueRefs.Ref qualified = GitHubIssueRefs.parse("acme/widgets#123").orElseThrow();
        assertFalse(qualified.isRepoRelative());
        assertEquals("acme", qualified.owner());
        assertEquals("widgets", qualified.repo());
        assertEquals(123, qualified.number());

        GitHubIssueRefs.Ref url =
                GitHubIssueRefs.parse("https://github.com/acme/widgets/issues/9").orElseThrow();
        assertEquals("acme", url.owner());
        assertEquals("widgets", url.repo());
        assertEquals(9, url.number());
    }

    @Test
    void parsesNothingFromAForeignReference() {
        assertEquals(Optional.empty(), GitHubIssueRefs.parse("PROJ-123"));
        assertEquals(Optional.empty(), GitHubIssueRefs.parse("https://acme.atlassian.net/browse/CS-1"));
    }

    /** Two spellings of one repository must dedupe across retrieval rounds. */
    @Test
    void normalizesCaseSoOneIssueIsNotFetchedTwice() {
        assertEquals(GitHubIssueRefs.normalize("acme/widgets#12"),
                GitHubIssueRefs.normalize("Acme/Widgets#12"));
    }

    @Test
    void allowsAnyRepositoryWhenTheAllowListIsBlank() {
        Set<String> none = GitHubIssueRefs.parseRepoAllowList("  ");
        assertTrue(GitHubIssueRefs.allows(none, "anyone", "anything"));
    }

    @Test
    void narrowsByOwnerOrByExactRepository() {
        Set<String> byOwner = GitHubIssueRefs.parseRepoAllowList("acme");
        assertTrue(GitHubIssueRefs.allows(byOwner, "acme", "widgets"));
        assertFalse(GitHubIssueRefs.allows(byOwner, "other", "widgets"));

        Set<String> byRepo = GitHubIssueRefs.parseRepoAllowList("acme/widgets, acme/tools");
        assertTrue(GitHubIssueRefs.allows(byRepo, "Acme", "Widgets"));
        assertFalse(GitHubIssueRefs.allows(byRepo, "acme", "secrets"));
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

```bash
./gradlew :spire-context-github:test
```

Expected: compile failure — `package dev.codespire.context.github does not exist`.

- [ ] **Step 4: Implement the grammar**

Create `spire-context-github/src/main/java/dev/codespire/context/github/GitHubIssueRefs.java`:

```java
package dev.codespire.context.github;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub issue-reference extraction and parsing — the counterpart to {@code JiraTicketKeys}, shared
 * by the worker (candidate extraction at diff-fetch) and the orchestrator (the on-demand preview).
 * One grammar, one home, no I/O.
 *
 * <p>Three forms carry a reference: the bare {@code #123}, which is relative to the repository the
 * review runs in; the qualified {@code owner/repo#123}; and a full issue or pull-request URL. The
 * bare form is by far the most common and the only one that needs to know which platform the review
 * is on, which is why {@link Ref#isRepoRelative()} exists.
 *
 * <p>Extraction favours recall, as the SPI intends: a wrong candidate costs one 404 the provider
 * skips. A CSS colour such as {@code #123456} therefore matches and costs one 404 — distinguishing
 * colours from issue numbers by content is not reliably possible, and {@link #MAX_REFS} bounds it.
 */
public final class GitHubIssueRefs {

    /**
     * Not preceded by a word character, '/' or '-', so an anchor ({@code page#3}), a path fragment
     * and a hyphenated token do not read as references — while {@code fixes #3} and {@code (#3)} do.
     */
    private static final Pattern BARE = Pattern.compile("(?<![\\w/-])#(\\d{1,7})\\b");

    /**
     * Exactly one slash: {@code owner/repo} is the whole of a GitHub namespace.
     *
     * <p>Not preceded by {@code /}, because a URL fragment is not a reference: without the guard,
     * {@code http://x/y#3} yields the false candidate {@code x/y#3}. The URL pattern above claims real
     * issue and pull-request links first, so anything reaching here with a slash in front of it is a
     * path or an anchor. A leading {@code :} is deliberately still allowed, so {@code ref:acme/repo#12}
     * extracts — narrowing that too would cost recall the design does not want to lose.
     */
    private static final Pattern QUALIFIED =
            Pattern.compile("(?<!/)\\b([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#(\\d{1,7})\\b");

    /**
     * Matched on the path, not the host, so github.com and a GitHub Enterprise host are covered by
     * one pattern. Pull requests share the issues id space and are resolved through the same call.
     */
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s<>\"')]*?/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/(?:issues|pull)/(\\d{1,7})\\b");

    /** Cost guard: a description listing dozens of issues must not become dozens of API calls. */
    private static final int MAX_REFS = 10;

    private GitHubIssueRefs() {
    }

    /**
     * One reference. {@code owner}/{@code repo} are null for the bare form, meaning "the repository
     * this review runs in" — which only the provider can supply, and only once it knows the review is
     * on GitHub.
     */
    public record Ref(String owner, String repo, int number) {

        public boolean isRepoRelative() {
            return owner == null || repo == null;
        }
    }

    /** Candidate references in the given texts (title/branch/description or retrieved bodies), capped. */
    public static Set<String> candidates(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // Richer forms first. The lookbehinds are what stop a bare pattern re-claiming a
            // fragment of a URL or qualified match; this order decides only which survives when
            // MAX_REFS truncates, and the more specific form is the better one to keep.
            collect(URL.matcher(text), found);
            collect(QUALIFIED.matcher(text), found);
            collect(BARE.matcher(text), found);
            if (found.size() >= MAX_REFS) {
                break;
            }
        }
        return found;
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find() && into.size() < MAX_REFS) {
            into.add(matcher.group());
        }
    }

    /** The reference as a repository plus number, or empty when the string is another source's. */
    public static Optional<Ref> parse(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String text = reference.strip();
        Matcher url = URL.matcher(text);
        if (url.find()) {
            return Optional.of(new Ref(url.group(1), url.group(2), Integer.parseInt(url.group(3))));
        }
        Matcher qualified = QUALIFIED.matcher(text);
        if (qualified.find()) {
            String[] parts = qualified.group(1).split("/");
            return Optional.of(new Ref(parts[0], parts[1], Integer.parseInt(qualified.group(2))));
        }
        Matcher bare = BARE.matcher(text);
        if (bare.find()) {
            return Optional.of(new Ref(null, null, Integer.parseInt(bare.group(1))));
        }
        return Optional.empty();
    }

    /**
     * Comparison form, so two spellings of one reference do not each start a retrieval round.
     * Repository names are case-insensitive on GitHub; a trailing slash is noise.
     */
    public static String normalize(String reference) {
        if (reference == null) {
            return "";
        }
        String text = reference.strip().toLowerCase(Locale.ROOT);
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    /** Parse the operator's optional allow-list ("acme, acme/widgets") into normalized entries. */
    public static Set<String> parseRepoAllowList(String raw) {
        Set<String> entries = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return entries;
        }
        for (String token : raw.split("[,\\s]+")) {
            String entry = token.strip().toLowerCase(Locale.ROOT);
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Whether this repository is in scope. An empty allow-list accepts everything on the configured
     * host — the generic behaviour, matching Jira's empty project-key list. An entry is either an
     * owner ({@code acme}, any repository under it) or an exact {@code owner/repo}.
     */
    public static boolean allows(Set<String> allowList, String owner, String repo) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        if (owner == null) {
            return false;
        }
        String lowerOwner = owner.toLowerCase(Locale.ROOT);
        if (allowList.contains(lowerOwner)) {
            return true;
        }
        return repo != null && allowList.contains(lowerOwner + "/" + repo.toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 5: Run it and confirm it passes**

```bash
./gradlew :spire-context-github:test
```

Expected: PASS, 13 tests. (The brief lists 11; the task's fix round added two more — a colon-recall test and a candidates/parse round-trip test.)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts LICENSING.md spire-context-github
git commit -m "Add the GitHub issue-reference grammar"
```

---

## Task 3: Extract the pinned HTTP client into `spire-http`

Before adding a third and fourth copy, give the guard one home. `JiraClient` and `ConfluenceClient` are
**byte-identical** apart from javadoc prose and one word in the sign-in hint (`site root` /
`wiki root`) — verify that yourself with the diff in Step 1 before changing anything. The risk this
removes is not line count: it is that a fix to the redirect or private-address guard must currently
land in every copy, and nothing fails if it lands in all but one.

**This task must be behaviour-preserving.** The existing Jira and Confluence test suites are the safety
net, and they must pass **completely unchanged**. If you find yourself editing one of those tests to
make it green, the refactor has altered behaviour — stop and report that instead of adjusting the test.

**Files:**
- Create: `spire-http/build.gradle.kts`
- Create: `spire-http/LICENSE` (copy of `spire-context-jira/LICENSE`)
- Create: `spire-http/src/main/java/dev/codespire/http/HttpFailures.java`
- Create: `spire-http/src/main/java/dev/codespire/http/PinnedJsonConfig.java`
- Create: `spire-http/src/main/java/dev/codespire/http/PinnedJsonClient.java`
- Test: `spire-http/src/test/java/dev/codespire/http/PinnedJsonClientTest.java`
- Modify: `spire-context-jira/src/main/java/dev/codespire/context/jira/JiraClient.java` (becomes a delegate)
- Modify: `spire-context-confluence/src/main/java/dev/codespire/context/confluence/ConfluenceClient.java` (same)
- Modify: `spire-context-jira/build.gradle.kts`, `spire-context-confluence/build.gradle.kts`
- Modify: `settings.gradle.kts`, `LICENSING.md`

**Interfaces:**
- Produces:
  - `interface HttpFailures { RuntimeException create(int status, String method, String path, String detail); }`
  - `record PinnedJsonConfig(String apiName, String baseUrl, String authorization, Map<String, String> headers, String rejectedCredentialHint)`
  - `class PinnedJsonClient` with `PinnedJsonClient(PinnedJsonConfig, ObjectMapper, HttpFailures)` and `JsonNode getJson(String path)`
- Tasks 4 and 7 build their clients on this instead of copying one.

- [ ] **Step 1: Confirm the two clients really are the same**

```bash
diff <(sed 's/Jira/X/g; s/jira/x/g' spire-context-jira/src/main/java/dev/codespire/context/jira/JiraClient.java) \
     <(sed 's/Confluence/X/g; s/confluence/x/g' spire-context-confluence/src/main/java/dev/codespire/context/confluence/ConfluenceClient.java)
```

Expected: differences only in the class javadoc and the one `site root` / `wiki root` word. If code
lines differ, say so in your report before proceeding — the extraction's shape depends on this.

- [ ] **Step 2: Scaffold the module**

Create `spire-http/build.gradle.kts`. Note it depends on **nothing in this repo** — it is a generic
JSON-over-HTTP helper with no domain knowledge, which is what keeps it usable by every adapter:

```kotlin
// Shared read-only JSON-over-HTTP client for the context and SCM adapters: one home for the
// host-pinned manual redirect handling and the private-address (SSRF) guard, so a fix to either
// lands once. Framework-free and domain-free — depends on Jackson and nothing in this repo.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
```

```bash
cp spire-context-jira/LICENSE spire-http/LICENSE
```

In `settings.gradle.kts`, before `include("spire-context-jira")`:

```kotlin
include("spire-http")
```

In `LICENSING.md`, before the `spire-context-jira` row:

```markdown
| `spire-http` | Apache-2.0 | Shared pinned-redirect JSON client every adapter builds on. No product value on its own. |
```

- [ ] **Step 3: Write the failing test**

Create `spire-http/src/test/java/dev/codespire/http/PinnedJsonClientTest.java`. These are the guard's
tests, and after this task they are the **only** copy of them:

```java
package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared guard's own tests. Every adapter's credential passes through this class, so the
 * host-pinning and private-address behaviour is asserted here once rather than in each adapter.
 */
class PinnedJsonClientTest {

    /** The adapter-supplied exception type, standing in for JiraApiException and friends. */
    static class TestApiException extends RuntimeException {
        final int status;

        TestApiException(int status, String method, String path, String detail) {
            super("Test API " + method + " " + path + " failed with HTTP " + status
                    + (detail == null || detail.isBlank() ? "" : ": " + detail));
            this.status = status;
        }
    }

    private static WireMockServer server;
    private static PinnedJsonClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = client("http://localhost:" + server.port());
    }

    private static PinnedJsonClient client(String baseUrl) {
        return new PinnedJsonClient(
                new PinnedJsonConfig("Test API", baseUrl, "Bearer TEST-token",
                        Map.of("Accept", "application/json"), "Check the base URL."),
                new ObjectMapper(), TestApiException::new);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void sendsTheConfiguredHeadersAndAuthorizationToTheApiHost() {
        server.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        JsonNode body = client.getJson("/thing");

        assertTrue(body.path("ok").asBoolean());
        server.verify(getRequestedFor(urlPathEqualTo("/thing"))
                .withHeader("Authorization", equalTo("Bearer TEST-token"))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void buildsTheAdaptersOwnExceptionCarryingTheStatus() {
        server.stubFor(get(urlPathEqualTo("/missing")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody("{}")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/missing"));

        assertEquals(404, thrown.status);
    }

    /**
     * A non-JSON 2xx means the request was redirected to authentication. Saying so beats surfacing a
     * parse error from deep inside the caller, and the configured hint says what to check.
     */
    @Test
    void reportsANonJsonSuccessAsARejectedCredentialWithTheConfiguredHint() {
        server.stubFor(get(urlPathEqualTo("/signin")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withBody("<html>Sign in</html>")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/signin"));

        assertEquals(200, thrown.status);
        assertTrue(thrown.getMessage().contains("expected JSON"));
        assertTrue(thrown.getMessage().contains("Check the base URL."));
    }

    /** Same-host redirects are followed, and the credential goes with them. */
    @Test
    void followsASameHostRedirectAndKeepsSendingTheCredential() {
        server.stubFor(get(urlPathEqualTo("/old")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "/new")));
        server.stubFor(get(urlPathEqualTo("/new")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        assertTrue(client.getJson("/old").path("ok").asBoolean());
        server.verify(getRequestedFor(urlPathEqualTo("/new"))
                .withHeader("Authorization", equalTo("Bearer TEST-token")));
    }

    /**
     * The SSRF guard. A redirect that leaves the configured host must not reach loopback or private
     * address space — that is how a redirect turns into a probe of the operator's own network.
     */
    @Test
    void refusesACrossHostRedirectIntoPrivateAddressSpace() {
        server.stubFor(get(urlPathEqualTo("/evil")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://127.0.0.1:9/internal")));

        TestApiException thrown = assertThrows(TestApiException.class, () -> client.getJson("/evil"));

        assertTrue(thrown.getMessage().contains("non-public address refused"));
    }

    /**
     * A malformed Location must not escape as an unchecked exception from the transport.
     * {@code URI.resolve("http://")} throws {@code IllegalArgumentException} before any host check can
     * run, so this covers the parse guard, not the pin.
     */
    @Test
    void refusesAnUnparseableRedirectTarget() {
        server.stubFor(get(urlPathEqualTo("/malformed")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/malformed"));

        assertTrue(thrown.getMessage().contains("unparseable redirect target refused"));
    }

    /**
     * An opaque scheme resolves cleanly but carries no host, so it reaches the host check rather than
     * the parse guard — the branch that refuses a redirect the pin cannot evaluate at all.
     */
    @Test
    void refusesARedirectToASchemeWithNoHost() {
        server.stubFor(get(urlPathEqualTo("/nohost")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "mailto:evil@example.invalid")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/nohost"));

        assertTrue(thrown.getMessage().contains("redirect without a host refused"));
    }

    /** A redirect loop must terminate rather than spin. */
    @Test
    void givesUpAfterTooManyRedirects() {
        server.stubFor(get(urlPathEqualTo("/loop")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "/loop")));

        assertEquals(310, assertThrows(TestApiException.class, () -> client.getJson("/loop")).status);
    }

    /**
     * The pin itself: a cross-host hop that IS public must still not carry the credential. WireMock
     * answers on 127.0.0.1, so this uses the loopback alias `localhost.` — a different host string
     * for the same server — to observe what a cross-origin request looks like.
     */
    @Test
    void doesNotSendTheCredentialToAHostOtherThanTheConfiguredOne() {
        PinnedJsonClient aliased = client("http://localhost." + ":" + server.port());
        server.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        aliased.getJson("/thing");

        server.verify(getRequestedFor(urlPathEqualTo("/thing"))
                .withHeader("Authorization", equalTo("Bearer TEST-token")));
    }

    /** A port written explicitly must count as the same origin as the scheme default. */
    @Test
    void treatsAnExplicitDefaultPortAsTheSameOrigin() {
        PinnedJsonConfig config = new PinnedJsonConfig("Test API", "https://example.invalid:443",
                "Bearer TEST-token", Map.of(), "hint");
        assertEquals("https://example.invalid:443", config.baseUrl());
    }
}
```

Note on the last two tests: `doesNotSendTheCredentialToAHostOtherThanTheConfiguredOne` as written
asserts the header IS present, because `localhost.` and `localhost` resolve to the same server and the
same-origin check compares host strings. **If that assertion fails, the pin is stricter than the test
assumes — report it rather than weakening the test.** Keep the test; its value is documenting which
comparison the pin makes.

- [ ] **Step 4: Run it and confirm it fails**

```bash
./gradlew :spire-http:test
```

Expected: compile failure — `package dev.codespire.http does not exist`.

- [ ] **Step 5: Write the failure factory and config**

Create `HttpFailures.java`:

```java
package dev.codespire.http;

/**
 * Builds the calling adapter's own exception for a non-2xx response or a refused redirect.
 *
 * <p>This exists so the shared client can be strict about transport while each adapter keeps its own
 * exception type: callers catch {@code JiraApiException} or {@code GitHubIssueApiException} narrowly,
 * and each type decides its own policy — notably whether a response-body snippet may appear in the
 * message, which differs because some APIs echo the rejected credential on a 401.
 */
public interface HttpFailures {

    /** @param detail a truncated, secret-free snippet or guard reason; null when there is none. */
    RuntimeException create(int status, String method, String path, String detail);
}
```

Create `PinnedJsonConfig.java`:

```java
package dev.codespire.http;

import java.util.Map;

/**
 * What the shared client needs to talk to one API host.
 *
 * @param apiName                 name used in I/O-failure messages ("Jira API", "GitHub API")
 * @param baseUrl                 API root; a trailing slash is trimmed
 * @param authorization           the finished {@code Authorization} header value — the adapter builds
 *                                it, so basic/bearer/token schemes stay the adapter's business
 * @param headers                 additional request headers, e.g. {@code Accept} and an API version
 * @param rejectedCredentialHint  what to tell the operator when a 2xx arrives that is not JSON, which
 *                                means the request reached a sign-in page
 */
public record PinnedJsonConfig(String apiName, String baseUrl, String authorization,
                               Map<String, String> headers, String rejectedCredentialHint) {

    public PinnedJsonConfig {
        require(apiName, "apiName");
        require(baseUrl, "baseUrl");
        require(authorization, "authorization");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PinnedJsonConfig '" + name + "' is required");
        }
    }
}
```

- [ ] **Step 6: Write the client**

Create `PinnedJsonClient.java`. This is `JiraClient`'s transport half, with the Jira-specific parts
replaced by config: the auth header value, the extra headers, the API name in I/O messages, the
sign-in hint, and the exception factory. Keep every guard exactly as it is today:

```java
package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Read-only JSON over HTTP against one pinned API host — the shared transport every context and SCM
 * adapter builds on.
 *
 * <p>Redirects are followed MANUALLY with host pinning: the bot's Authorization header is only ever
 * sent to the configured API host, never to a cross-host redirect target, and a cross-host hop into
 * loopback/link-local/private space is refused outright (SSRF guard). The credential is never logged.
 *
 * <p>This class exists so that guard has ONE home. It previously stood as an identical copy inside
 * each adapter, which meant a fix to it had to land in every copy and nothing failed if it landed in
 * all but one.
 */
public class PinnedJsonClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_REDIRECTS = 3;
    /** Not a real HTTP status — the status the redirect-loop guard reports through the failure factory. */
    private static final int TOO_MANY_REDIRECTS = 310;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String apiName;
    private final String authorization;
    private final Map<String, String> headers;
    private final String rejectedCredentialHint;
    private final HttpFailures failures;

    public PinnedJsonClient(PinnedJsonConfig config, ObjectMapper mapper, HttpFailures failures) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // manual, host-pinned
                .connectTimeout(TIMEOUT)
                .build();
        this.mapper = mapper;
        this.baseUri = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.apiName = config.apiName();
        this.authorization = config.authorization();
        this.headers = config.headers();
        this.rejectedCredentialHint = config.rejectedCredentialHint();
        this.failures = failures;
    }

    public JsonNode getJson(String path) {
        return parse(send("GET", path));
    }

    private String send(String method, String path) {
        URI target = URI.create(baseUri + path);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = execute(method, path, target);
            int status = response.statusCode();
            if (status / 100 == 3) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> failures.create(status, method, path, null));
                target = redirectTarget(target, location, status, method, path);
                requireSafeRedirectTarget(target, status, method, path);
                continue;
            }
            if (status / 100 != 2) {
                throw failures.create(status, method, path, bodySnippet(response.body()));
            }
            // A 2xx must be JSON. A non-JSON 2xx (an HTML sign-in page) means the request was
            // redirected to authentication — the token was not accepted. Surface it clearly here
            // instead of as a raw JSON parse error deep in the caller.
            String body = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!looksLikeJson(contentType, body)) {
                throw failures.create(status, method, path,
                        "expected JSON but received " + describeType(contentType)
                                + " — the request was redirected to a sign-in page, so the token was not "
                                + "accepted. " + rejectedCredentialHint + " Body starts: " + bodySnippet(body));
            }
            return body;
        }
        throw failures.create(TOO_MANY_REDIRECTS, method, path, null);
    }

    private static boolean looksLikeJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            return true;
        }
        String trimmed = body == null ? "" : body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String describeType(String contentType) {
        return contentType == null || contentType.isBlank() ? "a non-JSON response" : contentType;
    }

    /**
     * Resolve a {@code Location} header against the current target.
     *
     * <p>A malformed value ({@code Location: http://}) makes {@link URI#resolve} throw, which would
     * otherwise escape this transport as an unchecked exception, past callers that only expect the
     * adapter's own type. Refusing it here keeps every redirect failure one shape.
     */
    private URI redirectTarget(URI current, String location, int status, String method, String path) {
        try {
            return current.resolve(location);
        } catch (IllegalArgumentException e) {
            throw failures.create(status, method, path, "unparseable redirect target refused");
        }
    }

    /**
     * SSRF guard on redirect hops: a cross-host Location must not point into loopback/link-local/
     * private/unique-local address space. Same-host targets skip the check — the base host is
     * operator config, not attacker data, and dev/test legitimately run against WireMock on localhost.
     */
    private void requireSafeRedirectTarget(URI target, int status, String method, String path) {
        String host = target.getHost();
        if (host == null) {
            throw failures.create(status, method, path, "redirect without a host refused");
        }
        if (host.equalsIgnoreCase(baseUri.getHost())) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    throw failures.create(status, method, path,
                            "redirect to non-public address refused: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new UncheckedIOException(apiName + " " + method + " " + path
                    + " redirect target did not resolve", e);
        }
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        return raw.length == 16 && (raw[0] & 0xFE) == 0xFC; // IPv6 unique-local fc00::/7
    }

    /** Truncated response-body excerpt for error messages — no headers, so no secrets. */
    private static String bodySnippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String cleaned = body.replaceAll("\\s+", " ").strip();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500) + "...";
    }

    private HttpResponse<String> execute(String method, String path, URI target) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(TIMEOUT);
        headers.forEach(builder::header);
        if (sameOrigin(target)) {
            builder.header("Authorization", authorization); // pinned to the API host only
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(apiName + " " + method + " " + path + " I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + apiName, e);
        }
    }

    private boolean sameOrigin(URI target) {
        return baseUri.getScheme().equalsIgnoreCase(target.getScheme())
                && baseUri.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(baseUri) == effectivePort(target);
    }

    /** -1 (no explicit port) normalizes to the scheme default, so ":443" still matches. */
    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable " + apiName + " response", e);
        }
    }
}
```

- [ ] **Step 7: Run it and confirm it passes**

```bash
./gradlew :spire-http:test
```

Expected: PASS, 10 tests.

- [ ] **Step 8: Migrate `JiraClient` onto it**

Add the dependency in `spire-context-jira/build.gradle.kts`, beside the existing `api` line:

```kotlin
    implementation(project(":spire-http"))
```

Replace the whole body of `JiraClient.java`. Its public API — the constructor and `getJson` — is
unchanged, so `JiraContextProvider` and every existing test keep compiling untouched:

```java
package dev.codespire.context.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Thin read-only HTTP layer over the Jira REST API (v2 — its {@code description} comes back as a
 * plain string, unlike v3's Atlassian Document Format, so no ADF walker is needed and Data Center is
 * covered by the same paths).
 *
 * <p>Transport, host-pinned redirects and the SSRF guard live in {@link PinnedJsonClient}, shared with
 * every other adapter. What stays here is what is actually Jira's: the auth scheme (Cloud uses basic
 * with the account email, self-managed a bearer PAT) and the base-URL advice in the sign-in hint.
 */
public class JiraClient {

    private final PinnedJsonClient http;

    public JiraClient(JiraConfig config, ObjectMapper mapper) {
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("Jira API", config.baseUrl(), authHeader(config),
                        Map.of("Accept", "application/json"),
                        "Check the base URL is the Jira site root and the token has REST API access."),
                mapper, JiraApiException::new);
    }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }

    private static String authHeader(JiraConfig config) {
        if ("bearer".equals(config.authKind())) {
            return "Bearer " + config.secret();
        }
        String raw = config.username() + ":" + config.secret();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 9: Migrate `ConfluenceClient` the same way**

Add `implementation(project(":spire-http"))` to `spire-context-confluence/build.gradle.kts`, then
replace `ConfluenceClient.java`'s body with the same shape. Read the existing file for its exact
`authHeader` logic and reuse it verbatim; the two differences from Jira are the API name and the hint:

```java
                new PinnedJsonConfig("Confluence API", config.baseUrl(), authHeader(config),
                        Map.of("Accept", "application/json"),
                        "Check the base URL is the Confluence wiki root and the token has REST API access."),
                mapper, ConfluenceApiException::new);
```

Keep its class javadoc's Confluence-specific first paragraph (the `/rest/api/content` and XHTML notes)
and replace only the redirect/SSRF paragraph with a pointer to `PinnedJsonClient`.

- [ ] **Step 10: Prove the refactor changed no behaviour**

```bash
./gradlew :spire-http:test :spire-context-jira:test :spire-context-confluence:test
```

Expected: all PASS, with **no edits to any Jira or Confluence test file**. Confirm that:

```bash
git status --short spire-context-jira/src/test spire-context-confluence/src/test
```

Expected: empty output. A modified test file here means behaviour changed — report it instead of
committing.

- [ ] **Step 11: Full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts LICENSING.md spire-http spire-context-jira spire-context-confluence
git commit -m "Give the pinned-redirect HTTP guard one home"
```

Body: the two adapters carried byte-identical copies of the transport, so a fix to the redirect or
private-address guard had to land in each and nothing failed if it landed in all but one; behaviour is
unchanged, proven by both existing suites passing without edits.

---

## Task 4: GitHub HTTP client

**Files:**
- Create: `spire-context-github/src/main/java/dev/codespire/context/github/GitHubIssueConfig.java`
- Create: `spire-context-github/src/main/java/dev/codespire/context/github/GitHubIssueApiException.java`
- Create: `spire-context-github/src/main/java/dev/codespire/context/github/GitHubIssueClient.java`
- Test: `spire-context-github/src/test/java/dev/codespire/context/github/GitHubIssueClientTest.java`

**Interfaces:**
- Consumes: `GitHubIssueRefs.parseRepoAllowList` (Task 2).
- Produces: `new GitHubIssueConfig(String baseUrl, String authKind, String secret, Set<String> repoAllowList)`; `client.getJson(String path) -> JsonNode`; `GitHubIssueApiException.status() -> int`.

- [ ] **Step 1: Write the failing test**

Create `spire-context-github/src/test/java/dev/codespire/context/github/GitHubIssueClientTest.java`:

```java
package dev.codespire.context.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The HTTP layer: auth shape, status carrying, and the SSRF posture on redirects. */
class GitHubIssueClientTest {

    private static WireMockServer server;
    private static GitHubIssueClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = new GitHubIssueClient(
                new GitHubIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
                new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void sendsTheTokenAsABearerHeaderWithTheApiVersion() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"number\":1,\"title\":\"Widget spins backwards\"}")));

        JsonNode issue = client.getJson("/repos/acme/widgets/issues/1");

        assertEquals("Widget spins backwards", issue.path("title").asText());
        server.verify(getRequestedFor(urlPathEqualTo("/repos/acme/widgets/issues/1"))
                .withHeader("Authorization", equalTo("Bearer TEST-token"))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28")));
    }

    @Test
    void carriesTheStatusSoTheProviderCanSkipA404ButFailOnA401() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/404")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody("{}")));
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/401")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json").withBody("{}")));

        assertEquals(404, assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/404")).status());
        assertEquals(401, assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/401")).status());
    }

    /**
     * A 401 body can echo the token that was rejected, so no upstream body may reach the message an
     * auth failure produces. Statuses that are not credential outcomes may still carry a snippet.
     */
    @Test
    void neverPutsTheUpstreamBodyIntoAnAuthFailureMessage() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/9")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"Bad credentials for TEST-token\"}")));

        GitHubIssueApiException thrown = assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/9"));

        assertFalse(thrown.getMessage().contains("TEST-token"));
    }

    /** A non-JSON 2xx means the request landed on a sign-in page: the token was not accepted. */
    @Test
    void reportsANonJsonSuccessAsARejectedCredentialRatherThanAParseError() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/2")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withBody("<html>Sign in</html>")));

        GitHubIssueApiException thrown = assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/2"));

        assertEquals(200, thrown.status());
    }

    @Test
    void refusesACrossHostRedirectIntoPrivateAddressSpace() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/3")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://127.0.0.1:9/internal")));

        assertThrows(GitHubIssueApiException.class, () -> client.getJson("/repos/acme/widgets/issues/3"));
    }

    @Test
    void rejectsAConfigWhoseAuthKindIsNotBearer() {
        assertThrows(IllegalArgumentException.class,
                () -> new GitHubIssueConfig("https://api.github.com", "basic", "TEST-token", Set.of()));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :spire-context-github:test --tests '*GitHubIssueClientTest*'
```

Expected: compile failure — `cannot find symbol: class GitHubIssueClient`.

- [ ] **Step 3: Write the config**

Create `GitHubIssueConfig.java`:

```java
package dev.codespire.context.github;

import java.util.Set;

/**
 * GitHub issue-provider configuration, from the encrypted context-provider registry via the brokered
 * {@link dev.codespire.contract.context.ContextCredential} — NO defaults for credentials, fail fast
 * when unset (SECURITY.md).
 *
 * <p>{@code baseUrl} is the API root: {@code https://api.github.com} for github.com, or
 * {@code https://ghe.internal/api/v3} for GitHub Enterprise Server. Only {@code bearer} auth is
 * accepted — GitHub's basic auth is deprecated and a personal access token works on the bearer
 * header, so accepting {@code basic} would only offer a way to configure something that fails later.
 *
 * @param baseUrl        API root, no trailing slash required
 * @param authKind       must be {@code "bearer"}
 * @param secret         personal access token, classic or fine-grained
 * @param repoAllowList  optional owner or {@code owner/repo} entries; empty = any repository on this host
 */
public record GitHubIssueConfig(String baseUrl, String authKind, String secret,
                                Set<String> repoAllowList) {

    public GitHubIssueConfig {
        require(baseUrl, "baseUrl");
        require(authKind, "authKind");
        require(secret, "secret");
        if (!"bearer".equals(authKind)) {
            throw new IllegalArgumentException(
                    "GitHub issue context requires authKind 'bearer' (a personal access token), got '"
                            + authKind + "'. GitHub's basic auth is deprecated.");
        }
        repoAllowList = repoAllowList == null ? Set.of() : Set.copyOf(repoAllowList);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitHub issue config '" + name + "' is required");
        }
    }
}
```

- [ ] **Step 4: Write the exception**

Create `GitHubIssueApiException.java`:

```java
package dev.codespire.context.github;

/**
 * Non-2xx response from the GitHub REST API. {@code status} is surfaced so the provider can skip a
 * 404 (a typo'd reference) while an auth failure marks the whole contribution ERROR.
 *
 * <p>A 401 or 403 body can echo the token that was rejected, so {@code detail} is dropped for those
 * statuses: the message states the outcome and nothing the upstream said.
 */
public class GitHubIssueApiException extends RuntimeException {

    private final int status;

    public GitHubIssueApiException(int status, String method, String path) {
        this(status, method, path, null);
    }

    /** {@code detail} is a truncated, secret-free snippet — discarded entirely for auth statuses. */
    public GitHubIssueApiException(int status, String method, String path, String detail) {
        super("GitHub API " + method + " " + path + " failed with HTTP " + status
                + (isCredentialOutcome(status) || detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
    }

    public int status() {
        return status;
    }

    private static boolean isCredentialOutcome(int status) {
        return status == 401 || status == 403;
    }
}
```

- [ ] **Step 5: Write the client**

Add the shared transport to `spire-context-github/build.gradle.kts`, beside the existing `api` line
(the grammar needed no HTTP, so Task 2 did not add it):

```kotlin
    implementation(project(":spire-http"))
```

Create `GitHubIssueClient.java`. Transport, host-pinned redirects and the SSRF guard come from
`PinnedJsonClient` (Task 3); what belongs here is only what is GitHub's — the bearer scheme, the
`Accept` and API-version headers, and the base-URL advice in the sign-in hint:

```java
package dev.codespire.context.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.util.Map;

/**
 * Thin read-only HTTP layer over the GitHub REST API.
 *
 * <p>Transport, host-pinned redirects and the private-address (SSRF) guard live in
 * {@link PinnedJsonClient}, shared with every other adapter, so a fix to the guard lands once. What
 * stays here is what is actually GitHub's: bearer auth, the vendor {@code Accept} type, the pinned API
 * version, and the base-URL advice an operator needs when the token is refused.
 */
public class GitHubIssueClient {

    /** Pinning the API version keeps a future default change from silently altering response shapes. */
    private static final String API_VERSION = "2022-11-28";

    private final PinnedJsonClient http;

    public GitHubIssueClient(GitHubIssueConfig config, ObjectMapper mapper) {
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("GitHub API", config.baseUrl(), "Bearer " + config.secret(),
                        Map.of("Accept", "application/vnd.github+json",
                                "X-GitHub-Api-Version", API_VERSION),
                        "Check the base URL is the API root (…/api/v3 on Enterprise Server) and the "
                                + "token can read issues."),
                mapper, GitHubIssueApiException::new);
    }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }
}
```

Two tests in Step 1 — the private-address redirect refusal and the non-JSON-2xx report — now duplicate
what `spire-http` tests directly. **Keep them anyway.** They are wiring checks: the guard is only
present if this adapter actually routes through `PinnedJsonClient`, and a client constructed wrongly
would lose it silently with every other test still green.

- [ ] **Step 6: Run it and confirm it passes**

```bash
./gradlew :spire-context-github:test
```

Expected: PASS, 19 tests (13 from Task 2 + 6 here).

- [ ] **Step 7: Commit**

```bash
git add spire-context-github
git commit -m "Add the GitHub issue API client"
```

---

## Task 5: GitHub issue context provider

The task that makes the guard real. Its cross-wire test is the regression test for the whole design.

**Files:**
- Create: `spire-context-github/src/main/java/dev/codespire/context/github/GitHubIssueContextProvider.java`
- Create: `spire-context-github/src/main/java/dev/codespire/context/github/GitHubIssueReferenceSource.java`
- Test: `spire-context-github/src/test/java/dev/codespire/context/github/GitHubIssueContextProviderTest.java`

**Interfaces:**
- Consumes: `GitHubIssueRefs` (Task 2), `GitHubIssueClient`/`GitHubIssueConfig` (Task 4), `ContextRequest.scmType()` (Task 1).
- Produces: `GitHubIssueContextProvider.SOURCE == "GITHUB_ISSUES"`; `new GitHubIssueContextProvider(GitHubIssueConfig, ObjectMapper)`; `new GitHubIssueReferenceSource()`.

- [ ] **Step 1: Write the failing test**

Create `spire-context-github/src/test/java/dev/codespire/context/github/GitHubIssueContextProviderTest.java`:

```java
package dev.codespire.context.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHubIssueContextProvider against a WireMock GitHub. */
class GitHubIssueContextProviderTest {

    private static WireMockServer server;
    private static GitHubIssueContextProvider provider;
    private static final RepoRef REPO = new RepoRef("acme", "widgets");

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new GitHubIssueContextProvider(
                new GitHubIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
                new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private static ContextRequest request(Set<String> references, ScmType scmType) {
        return new ContextRequest("review::acme/widgets#7", REPO, 7, "abc123", references,
                Set.of(), scmType);
    }

    private static void stubIssue(int number, String body) {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/" + number))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void stubNoComments(int number) {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/" + number + "/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
    }

    // ---------------------------------------------------------------- the guard

    /**
     * The reason scmType exists. "acme/widgets" is a real repository name on more than one platform,
     * so resolving a bare "#123" for a review that is NOT on GitHub would fetch a real but unrelated
     * issue and present it as context. Asserting on zero requests, not on an empty contribution: a
     * provider that fetched and then discarded would still be wrong.
     */
    @Test
    void resolvesNoBareReferenceForAReviewOnAnotherPlatform() {
        stubIssue(123, "{\"number\":123,\"title\":\"Wrong platform\"}");

        ContextRequest foreign = request(Set.of("#123"), ScmType.BITBUCKET_CLOUD);

        assertFalse(provider.supports(foreign));
        assertEquals(0, server.getAllServeEvents().size());
    }

    /** And declines just as firmly when the platform could not be determined at all. */
    @Test
    void resolvesNoBareReferenceWhenThePlatformIsUnknown() {
        assertFalse(provider.supports(request(Set.of("#123"), null)));
        assertEquals(0, server.getAllServeEvents().size());
    }

    /**
     * A qualified reference names its own repository, so it is unambiguous regardless of where the
     * review runs — an author who writes "acme/widgets#12" on a Bitbucket PR meant that GitHub issue.
     */
    @Test
    void stillResolvesAQualifiedReferenceForAReviewOnAnotherPlatform() {
        assertTrue(provider.supports(request(Set.of("acme/widgets#12"), ScmType.BITBUCKET_CLOUD)));
    }

    @Test
    void ignoresReferencesBelongingToAnotherSource() {
        assertFalse(provider.supports(request(Set.of("PROJ-123"), ScmType.GITHUB)));
    }

    @Test
    void ignoresARepositoryOutsideTheAllowList() {
        GitHubIssueContextProvider narrowed = new GitHubIssueContextProvider(
                new GitHubIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token",
                        GitHubIssueRefs.parseRepoAllowList("other")),
                new ObjectMapper());

        assertFalse(narrowed.supports(request(Set.of("#123"), ScmType.GITHUB)));
    }

    // ------------------------------------------------------------- the fetching

    @Test
    void resolvesAnIssueIntoAContextItemWithItsRecentComments() {
        stubIssue(123, """
                {"number":123,"title":"Widget spins backwards","state":"open",
                 "body":"The widget spins backwards above 40rpm.",
                 "labels":[{"name":"bug"},{"name":"priority"}],
                 "html_url":"https://github.com/acme/widgets/issues/123"}
                """);
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/123/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        [{"body":"Reproduced on the bench rig.","user":{"login":"dana"}},
                         {"body":"Root cause is the gear ratio.","user":{"login":"ines"}}]
                        """)));

        ContextContribution contribution =
                provider.contribute(request(Set.of("#123"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(1, contribution.items().size());
        ContextItem item = contribution.items().get(0);
        assertEquals("ISSUE", item.kind());
        assertTrue(item.title().contains("#123"));
        assertTrue(item.title().contains("Widget spins backwards"));
        assertTrue(item.body().contains("spins backwards above 40rpm"));
        assertTrue(item.body().contains("bug"), "labels carry triage the diff cannot show");
        assertTrue(item.body().contains("Root cause is the gear ratio"), "comments carry the decision");
        assertTrue(item.body().contains("ines"));
        assertEquals("https://github.com/acme/widgets/issues/123", item.uri());
    }

    /**
     * Pull requests share the issues id space. Resolve rather than filter — "supersedes #120" is real
     * context — but label the kind honestly so the model is not told a change request is a requirement.
     */
    @Test
    void labelsAPullRequestAsOneRatherThanCallingItAnIssue() {
        stubIssue(120, """
                {"number":120,"title":"Rework the gearbox","state":"closed","body":"Supersedes the old ratio.",
                 "pull_request":{"url":"https://api.github.com/repos/acme/widgets/pulls/120"},
                 "html_url":"https://github.com/acme/widgets/pull/120"}
                """);
        stubNoComments(120);

        ContextContribution contribution =
                provider.contribute(request(Set.of("#120"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals("PULL_REQUEST", contribution.items().get(0).kind());
    }

    /** A typo'd reference must not cost the siblings that did resolve. */
    @Test
    void skipsAMissingReferenceAndKeepsTheRest() {
        stubIssue(1, "{\"number\":1,\"title\":\"Real issue\",\"body\":\"Present.\"}");
        stubNoComments(1);
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/999"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        ContextContribution contribution = provider
                .contribute(request(Set.of("#1", "#999"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(List.of("Real issue"),
                contribution.items().stream().map(i -> i.title().replaceAll("^#\\d+ — ", "")).toList());
    }

    /** An auth failure applies to every reference, so the contribution is ERROR, not a silent EMPTY. */
    @Test
    void reportsAnAuthFailureAsAnErrorContribution() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/1"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        ContextContribution contribution =
                provider.contribute(request(Set.of("#1"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.ERROR, contribution.status());
        assertTrue(contribution.items().isEmpty());
    }

    /** Comments are enrichment: losing them must not lose the issue itself. */
    @Test
    void keepsTheIssueWhenItsCommentsCannotBeRead() {
        stubIssue(5, "{\"number\":5,\"title\":\"Readable\",\"body\":\"Body present.\"}");
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/5/comments"))
                .willReturn(aResponse().withStatus(500).withBody("{}")));

        ContextContribution contribution =
                provider.contribute(request(Set.of("#5"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertTrue(contribution.items().get(0).body().contains("Body present"));
    }

    @Test
    void reportsItsSourceUnderTheNameTheAggregatorMerges() {
        assertEquals("GITHUB_ISSUES", provider.source());
        assertEquals("GITHUB_ISSUES", new GitHubIssueReferenceSource().source());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :spire-context-github:test --tests '*GitHubIssueContextProviderTest*'
```

Expected: compile failure — `cannot find symbol: class GitHubIssueContextProvider`.

- [ ] **Step 3: Write the reference source**

Create `GitHubIssueReferenceSource.java`:

```java
package dev.codespire.context.github;

import dev.codespire.contract.port.ContextReferenceSource;

import java.util.Set;

/**
 * Recognises GitHub issue references ({@code #123}, {@code owner/repo#123}, issue and pull-request
 * URLs) in free text.
 *
 * <p>Stateless and credential-free, so the pipeline can extract references at diff-fetch time before
 * any credential is brokered. Narrowing — to this host, to the allowed repositories, and to the
 * platform the review actually runs on — happens later in {@link GitHubIssueContextProvider}, which
 * is the part that has configuration and knows the review's platform.
 */
public final class GitHubIssueReferenceSource implements ContextReferenceSource {

    @Override
    public String source() {
        return GitHubIssueContextProvider.SOURCE;
    }

    @Override
    public Set<String> referencesIn(String... texts) {
        return GitHubIssueRefs.candidates(texts);
    }

    /** Repository names compare case-insensitively, so two spellings dedupe. */
    @Override
    public String normalize(String reference) {
        return GitHubIssueRefs.normalize(reference);
    }
}
```

- [ ] **Step 4: Write the provider**

Create `GitHubIssueContextProvider.java`:

```java
package dev.codespire.context.github;

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
 * Resolves the PR's referenced GitHub issues and pull requests into {@link ContextItem}s for the
 * review prompt. Built per {@code GatherContext} command from the brokered credential (like the SCM
 * adapters), NOT a long-lived singleton — the credential is workspace-scoped and decrypted only
 * inside the worker.
 *
 * <p>Retrieved text is UNTRUSTED (SECURITY.md) — the prompt builder fences it; this provider only
 * shapes it. One bad reference (404/typo) is skipped, not fatal; an auth failure yields an
 * {@code ERROR} contribution so the aggregator records the miss without aborting the review.
 */
public class GitHubIssueContextProvider implements ContextProvider {

    public static final String SOURCE = "GITHUB_ISSUES";
    private static final String KIND_ISSUE = "ISSUE";
    private static final String KIND_PULL_REQUEST = "PULL_REQUEST";
    /** Guard one oversized issue from dominating the shared context budget. */
    private static final int MAX_BODY_CHARS = 4_000;
    /** Include the last few comments — where the real decisions often live — bounded to limit noise. */
    private static final int MAX_COMMENTS = 5;
    private static final int MAX_COMMENT_CHARS = 500;
    /** Cost guard: a description listing dozens of issues must not become dozens of API calls. */
    private static final int MAX_REFERENCES = 10;

    private final GitHubIssueClient client;
    private final Set<String> repoAllowList;

    public GitHubIssueContextProvider(GitHubIssueConfig config, ObjectMapper mapper) {
        this.client = new GitHubIssueClient(config, mapper);
        this.repoAllowList = config.repoAllowList();
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

    /**
     * The request's candidates narrowed to what this provider can actually resolve. The request
     * carries every source's candidates, so recognising our own is part of the job.
     *
     * <p>Order is preserved and the result capped, so a link farm cannot drive an unbounded fan-out.
     */
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
     * A reference plus the repository it belongs to, or empty when this provider cannot say.
     *
     * <p>A repo-relative reference borrows the review's own repository, which is only the right
     * repository when the review runs on THIS platform — the same {@code workspace/slug} exists on
     * other hosts, so resolving it elsewhere would fetch a real but unrelated issue. A qualified
     * reference or a URL names its own repository, so it needs no such guard: an author who writes
     * {@code acme/widgets#12} meant that repository wherever they wrote it.
     */
    private Optional<Target> resolve(String reference, ContextRequest request) {
        return GitHubIssueRefs.parse(reference).flatMap(ref -> {
            if (!ref.isRepoRelative()) {
                return GitHubIssueRefs.allows(repoAllowList, ref.owner(), ref.repo())
                        ? Optional.of(new Target(ref.owner(), ref.repo(), ref.number()))
                        : Optional.empty();
            }
            RepoRef repo = request.repo();
            if (request.scmType() != ScmType.GITHUB || repo == null) {
                return Optional.empty();
            }
            return GitHubIssueRefs.allows(repoAllowList, repo.workspace(), repo.slug())
                    ? Optional.of(new Target(repo.workspace(), repo.slug(), ref.number()))
                    : Optional.empty();
        });
    }

    private record Target(String owner, String repo, int number) {

        /**
         * Owner and repository are normalized to lower case here, at the single point every
         * {@link Target} is built, because GitHub treats them case-insensitively.
         *
         * <p>Normalizing only {@link #key()} is not enough: de-dup would pick one survivor, but that
         * survivor's casing — which {@link #path()} uses to build the fetch URL — would be whichever
         * reference happened to resolve first, i.e. arbitrary and dependent on set iteration order.
         * Canonicalizing at construction keeps the key and the path in agreement.
         */
        static Target of(String owner, String repo, int number) {
            return new Target(owner.toLowerCase(Locale.ROOT), repo.toLowerCase(Locale.ROOT), number);
        }

        /** De-dup key: one issue referenced two ways (case or form) must cost one fetch, one item. */
        String key() {
            return owner + "/" + repo + "#" + number;
        }

        String path() {
            return "/repos/" + owner + "/" + repo + "/issues/" + number;
        }
    }

    private ContextContribution fetch(ContextRequest request) {
        long start = System.nanoTime();
        List<ContextItem> items = new ArrayList<>();
        try {
            for (Target target : resolvable(request)) {
                resolveItem(target).ifPresent(items::add);
            }
        } catch (GitHubIssueApiException e) {
            // Auth/config failure applies to every reference — record ERROR, don't abort the review.
            return new ContextContribution(SOURCE, ContribStatus.ERROR, List.of(), latencyMs(start));
        }
        ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
        return new ContextContribution(SOURCE, status, items, latencyMs(start));
    }

    /** @return the issue or pull request as a ContextItem, or empty when it does not resolve (404). */
    private Optional<ContextItem> resolveItem(Target target) {
        JsonNode issue;
        try {
            issue = client.getJson(target.path());
        } catch (GitHubIssueApiException e) {
            if (e.status() == 404) {
                return Optional.empty(); // typo'd or unreachable reference — skip, keep the rest
            }
            throw e; // auth/5xx bubbles up to mark the whole contribution
        }
        // GitHub returns pull requests from the issues endpoint; this key is what distinguishes them.
        boolean isPullRequest = !issue.path("pull_request").isMissingNode();
        String state = issue.path("state").asText("");
        String body = clip(issue.path("body").asText(""), MAX_BODY_CHARS);

        StringBuilder rendered = new StringBuilder();
        rendered.append("State: ").append(state.isBlank() ? "?" : state);
        String labels = labelsOf(issue);
        if (!labels.isBlank()) {
            rendered.append(" | Labels: ").append(labels);
        }
        rendered.append('\n');
        if (!body.isBlank()) {
            rendered.append('\n').append(body);
        }
        appendRecentComments(rendered, target);

        String title = "#" + target.number()
                + (issue.path("title").asText("").isBlank() ? "" : " — " + issue.path("title").asText());
        String uri = issue.path("html_url").asText("");
        return Optional.of(new ContextItem(isPullRequest ? KIND_PULL_REQUEST : KIND_ISSUE,
                title, rendered.toString().strip(), uri.isBlank() ? null : uri));
    }

    private static String labelsOf(JsonNode issue) {
        JsonNode labels = issue.path("labels");
        if (!labels.isArray() || labels.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode label : labels) {
            String name = label.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return String.join(", ", names);
    }

    /**
     * Append the most recent comments with author and a truncated body, bounded so a chatty issue
     * cannot crowd out the diff.
     *
     * <p>Comments are a second call, so they can fail while the issue itself read fine. They are
     * enrichment: a failure here drops them and keeps the issue rather than losing both.
     */
    private void appendRecentComments(StringBuilder body, Target target) {
        JsonNode comments;
        try {
            comments = client.getJson(target.path() + "/comments?per_page=100");
        } catch (RuntimeException e) {
            return;
        }
        if (!comments.isArray() || comments.isEmpty()) {
            return;
        }
        int from = Math.max(0, comments.size() - MAX_COMMENTS); // returned oldest-first: take the tail
        StringBuilder rendered = new StringBuilder();
        for (int i = from; i < comments.size(); i++) {
            JsonNode comment = comments.get(i);
            String text = clip(comment.path("body").asText(""), MAX_COMMENT_CHARS);
            if (text.isBlank()) {
                continue;
            }
            String author = comment.path("user").path("login").asText("");
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
```

- [ ] **Step 5: Run it and confirm it passes**

```bash
./gradlew :spire-context-github:test
```

Expected: PASS, 30 tests.

- [ ] **Step 6: Commit**

```bash
git add spire-context-github
git commit -m "Resolve GitHub issue references into review context"
```

Body: note that a bare reference is gated on the review's platform because the same workspace/slug exists on several hosts, while a qualified reference is unambiguous and needs no gate.

---

## Task 6: GitLab reference grammar

**Files:**
- Create: `spire-context-gitlab/build.gradle.kts` (identical to Task 2's but for GitLab — full content below)
- Create: `spire-context-gitlab/LICENSE`
- Create: `spire-context-gitlab/src/main/java/dev/codespire/context/gitlab/GitLabIssueRefs.java`
- Modify: `settings.gradle.kts`, `LICENSING.md`
- Test: `spire-context-gitlab/src/test/java/dev/codespire/context/gitlab/GitLabIssueRefsTest.java`

**Interfaces:**
- Produces:
  - `enum GitLabIssueRefs.Kind { ISSUE, MERGE_REQUEST, EPIC }`
  - `record GitLabIssueRefs.Ref(Kind kind, String projectPath, int number)` with `boolean isProjectRelative()`
  - `static Set<String> candidates(String... texts)`, `static Optional<Ref> parse(String)`, `static String normalize(String)`
  - `static Set<String> parseProjectAllowList(String raw)`, `static boolean allows(Set<String>, String projectPath)`
  - `static List<String> ancestorGroups(String projectPath)`

- [ ] **Step 1: Scaffold the module**

Create `spire-context-gitlab/build.gradle.kts`:

```kotlin
// GitLab issue context provider: resolves the issue, merge-request and epic
// references in an MR's text into ContextItems for the review prompt (CONTRACT §7/§8).
// Framework-free library (JDK HttpClient + Jackson); CDI wiring happens in the
// service that hosts it (spire-review-worker).
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":spire-contract"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
```

```bash
cp spire-context-jira/LICENSE spire-context-gitlab/LICENSE
```

`settings.gradle.kts`: add `include("spire-context-gitlab")`.
`LICENSING.md`: add `| `spire-context-gitlab` | Apache-2.0 | Same. |`.

- [ ] **Step 2: Write the failing test**

Create `spire-context-gitlab/src/test/java/dev/codespire/context/gitlab/GitLabIssueRefsTest.java`:

```java
package dev.codespire.context.gitlab;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitLab's grammar carries three sigils where GitHub has one, and its namespaces nest. These tests
 * pin the sigil-to-kind mapping and the nesting, because getting either wrong silently fetches the
 * wrong object type or the wrong project.
 */
class GitLabIssueRefsTest {

    @Test
    void mapsEachSigilToItsOwnKind() {
        assertEquals(GitLabIssueRefs.Kind.ISSUE, GitLabIssueRefs.parse("#12").orElseThrow().kind());
        assertEquals(GitLabIssueRefs.Kind.MERGE_REQUEST, GitLabIssueRefs.parse("!34").orElseThrow().kind());
        assertEquals(GitLabIssueRefs.Kind.EPIC, GitLabIssueRefs.parse("&7").orElseThrow().kind());
    }

    @Test
    void findsAllThreeSigilsInProse() {
        assertEquals(Set.of("#12", "!34", "&7"),
                GitLabIssueRefs.candidates("closes #12, follows !34, part of &7"));
    }

    /** A sigil glued to a word is punctuation, not a reference. */
    @Test
    void ignoresSigilsInsideWords() {
        assertTrue(GitLabIssueRefs.candidates("abc#1 x!2 y&3").isEmpty());
    }

    /**
     * A URL fragment is not a qualified reference. Without the {@code (?<!/)} guard on the qualified
     * pattern, {@code http://x/y#3} yields the false candidate {@code x/y#3} — the GitHub adapter's
     * equivalent test caught exactly that, and this grammar has the same shape.
     */
    @Test
    void ignoresAQualifiedLookAlikeInsideAUrlFragment() {
        assertTrue(GitLabIssueRefs.candidates("http://x/y#3").isEmpty());
    }

    /** But a colon before a qualified reference is prose, not a URL — it must still extract. */
    @Test
    void stillFindsAQualifiedReferenceAfterAColon() {
        assertEquals(Set.of("acme/widgets#12"), GitLabIssueRefs.candidates("ref:acme/widgets#12"));
    }

    /** Nested groups are the normal case on GitLab, so the qualified form must accept many slashes. */
    @Test
    void parsesAQualifiedReferenceAcrossNestedGroups() {
        GitLabIssueRefs.Ref ref = GitLabIssueRefs.parse("acme/tools/widgets#12").orElseThrow();
        assertFalse(ref.isProjectRelative());
        assertEquals("acme/tools/widgets", ref.projectPath());
        assertEquals(12, ref.number());
        assertEquals(GitLabIssueRefs.Kind.ISSUE, ref.kind());
    }

    @Test
    void parsesTheThreeUrlShapesIncludingOnASelfManagedHost() {
        GitLabIssueRefs.Ref issue = GitLabIssueRefs
                .parse("https://gitlab.example.invalid/acme/tools/widgets/-/issues/12").orElseThrow();
        assertEquals("acme/tools/widgets", issue.projectPath());
        assertEquals(GitLabIssueRefs.Kind.ISSUE, issue.kind());

        GitLabIssueRefs.Ref mr = GitLabIssueRefs
                .parse("https://gitlab.com/acme/widgets/-/merge_requests/34").orElseThrow();
        assertEquals(GitLabIssueRefs.Kind.MERGE_REQUEST, mr.kind());
        assertEquals(34, mr.number());

        GitLabIssueRefs.Ref epic = GitLabIssueRefs
                .parse("https://gitlab.com/groups/acme/-/epics/7").orElseThrow();
        assertEquals(GitLabIssueRefs.Kind.EPIC, epic.kind());
        assertEquals("acme", epic.projectPath());
    }

    @Test
    void parsesNothingFromAnotherSourcesReference() {
        assertEquals(Optional.empty(), GitLabIssueRefs.parse("PROJ-123"));
        assertEquals(Optional.empty(), GitLabIssueRefs.parse("plain text"));
    }

    @Test
    void capsCandidates() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            text.append(" #").append(i);
        }
        assertEquals(10, GitLabIssueRefs.candidates(text.toString()).size());
    }

    @Test
    void normalizesCaseAndTrailingSlash() {
        assertEquals(GitLabIssueRefs.normalize("Acme/Widgets#12"),
                GitLabIssueRefs.normalize("acme/widgets#12"));
        assertEquals(GitLabIssueRefs.normalize("https://gitlab.com/acme/widgets/-/issues/1/"),
                GitLabIssueRefs.normalize("https://gitlab.com/acme/widgets/-/issues/1"));
    }

    /**
     * An epic belongs to a group, and a project path does not say which ancestor owns it. Nearest
     * first, then the top-level group — ordered, because the provider tries them in turn.
     */
    @Test
    void listsAncestorGroupsNearestFirst() {
        assertEquals(List.of("acme/tools", "acme"), GitLabIssueRefs.ancestorGroups("acme/tools/widgets"));
        assertEquals(List.of("acme"), GitLabIssueRefs.ancestorGroups("acme/widgets"));
        assertEquals(List.of(), GitLabIssueRefs.ancestorGroups("widgets"));
    }

    @Test
    void narrowsByGroupPrefixOrExactProject() {
        Set<String> byGroup = GitLabIssueRefs.parseProjectAllowList("acme");
        assertTrue(GitLabIssueRefs.allows(byGroup, "acme/tools/widgets"));
        assertFalse(GitLabIssueRefs.allows(byGroup, "other/widgets"));

        Set<String> exact = GitLabIssueRefs.parseProjectAllowList("acme/widgets");
        assertTrue(GitLabIssueRefs.allows(exact, "Acme/Widgets"));
        assertFalse(GitLabIssueRefs.allows(exact, "acme/secrets"));

        assertTrue(GitLabIssueRefs.allows(GitLabIssueRefs.parseProjectAllowList(""), "anyone/anything"));
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

```bash
./gradlew :spire-context-gitlab:test
```

Expected: compile failure — package does not exist.

- [ ] **Step 4: Implement the grammar**

Create `spire-context-gitlab/src/main/java/dev/codespire/context/gitlab/GitLabIssueRefs.java`:

```java
package dev.codespire.context.gitlab;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab reference extraction and parsing — the counterpart to {@code JiraTicketKeys}, shared by the
 * worker (extraction at diff-fetch) and the orchestrator (the preview). One grammar, one home, no I/O.
 *
 * <p>GitLab spells three different objects with three sigils: {@code #12} an issue, {@code !34} a
 * merge request, {@code &7} an epic. Each is relative to the project (or, for an epic, the project's
 * ancestor group) unless the reference qualifies itself. Namespaces nest, so the qualified form takes
 * one or more slashes — unlike GitHub, where {@code owner/repo} is the whole namespace.
 *
 * <p>Extraction favours recall, as the SPI intends: a wrong candidate costs one 404 the provider
 * skips, and {@link #MAX_REFS} bounds the cost.
 */
public final class GitLabIssueRefs {

    /** What a sigil refers to. Each resolves through a different API path. */
    public enum Kind {
        ISSUE, MERGE_REQUEST, EPIC
    }

    private static final Pattern BARE_ISSUE = Pattern.compile("(?<![\\w/&!-])#(\\d{1,7})\\b");
    private static final Pattern BARE_MERGE_REQUEST = Pattern.compile("(?<![\\w/#&-])!(\\d{1,7})\\b");
    private static final Pattern BARE_EPIC = Pattern.compile("(?<![\\w/#!-])&(\\d{1,7})\\b");

    /**
     * One or more slashes: GitLab namespaces nest arbitrarily deep.
     *
     * <p>Not preceded by {@code /}, for the same reason as the GitHub adapter's equivalent: without the
     * guard, {@code http://x/y#3} yields the false candidate {@code x/y#3}. A leading {@code :} stays
     * allowed so {@code ref:acme/proj#12} extracts.
     */
    private static final Pattern QUALIFIED_ISSUE =
            Pattern.compile("(?<!/)\\b((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+)#(\\d{1,7})\\b");

    // Matched on the path, so gitlab.com and a self-managed host share one pattern each.
    private static final Pattern URL_ISSUE =
            Pattern.compile("https?://[^\\s<>\"')]*?/((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+)/-/issues/(\\d{1,7})\\b");
    private static final Pattern URL_MERGE_REQUEST = Pattern.compile(
            "https?://[^\\s<>\"')]*?/((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+)/-/merge_requests/(\\d{1,7})\\b");
    private static final Pattern URL_EPIC = Pattern.compile(
            "https?://[^\\s<>\"')]*?/groups/((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+)/-/epics/(\\d{1,7})\\b");

    private static final int MAX_REFS = 10;

    private GitLabIssueRefs() {
    }

    /**
     * One reference. {@code projectPath} is null for a bare form, meaning "the project this review
     * runs in" — which only the provider can supply, and only once it knows the review is on GitLab.
     * For an epic URL it is the group named in the URL.
     */
    public record Ref(Kind kind, String projectPath, int number) {

        public boolean isProjectRelative() {
            return projectPath == null;
        }
    }

    /** Candidate references in the given texts, capped. */
    public static Set<String> candidates(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // Richer forms first. The lookbehinds are what stop a bare pattern re-claiming a
            // fragment of a URL or qualified match; this order decides only which survives when
            // MAX_REFS truncates, and the more specific form is the better one to keep.
            collect(URL_EPIC.matcher(text), found);
            collect(URL_MERGE_REQUEST.matcher(text), found);
            collect(URL_ISSUE.matcher(text), found);
            collect(QUALIFIED_ISSUE.matcher(text), found);
            collect(BARE_ISSUE.matcher(text), found);
            collect(BARE_MERGE_REQUEST.matcher(text), found);
            collect(BARE_EPIC.matcher(text), found);
            if (found.size() >= MAX_REFS) {
                break;
            }
        }
        return found;
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find() && into.size() < MAX_REFS) {
            into.add(matcher.group());
        }
    }

    /** The reference as kind, project/group and number, or empty when it is another source's. */
    public static Optional<Ref> parse(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String text = reference.strip();
        Optional<Ref> url = parseUrl(text);
        if (url.isPresent()) {
            return url;
        }
        Matcher qualified = QUALIFIED_ISSUE.matcher(text);
        if (qualified.find()) {
            return Optional.of(new Ref(Kind.ISSUE, qualified.group(1),
                    Integer.parseInt(qualified.group(2))));
        }
        Matcher issue = BARE_ISSUE.matcher(text);
        if (issue.find()) {
            return Optional.of(new Ref(Kind.ISSUE, null, Integer.parseInt(issue.group(1))));
        }
        Matcher mergeRequest = BARE_MERGE_REQUEST.matcher(text);
        if (mergeRequest.find()) {
            return Optional.of(new Ref(Kind.MERGE_REQUEST, null,
                    Integer.parseInt(mergeRequest.group(1))));
        }
        Matcher epic = BARE_EPIC.matcher(text);
        if (epic.find()) {
            return Optional.of(new Ref(Kind.EPIC, null, Integer.parseInt(epic.group(1))));
        }
        return Optional.empty();
    }

    private static Optional<Ref> parseUrl(String text) {
        Matcher epic = URL_EPIC.matcher(text);
        if (epic.find()) {
            return Optional.of(new Ref(Kind.EPIC, epic.group(1), Integer.parseInt(epic.group(2))));
        }
        Matcher mergeRequest = URL_MERGE_REQUEST.matcher(text);
        if (mergeRequest.find()) {
            return Optional.of(new Ref(Kind.MERGE_REQUEST, mergeRequest.group(1),
                    Integer.parseInt(mergeRequest.group(2))));
        }
        Matcher issue = URL_ISSUE.matcher(text);
        if (issue.find()) {
            return Optional.of(new Ref(Kind.ISSUE, issue.group(1), Integer.parseInt(issue.group(2))));
        }
        return Optional.empty();
    }

    /** Comparison form, so two spellings of one reference do not each start a retrieval round. */
    public static String normalize(String reference) {
        if (reference == null) {
            return "";
        }
        String text = reference.strip().toLowerCase(Locale.ROOT);
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * The groups that could own an epic referenced from this project, nearest ancestor first. GitLab
     * epics live at group level and a project path does not say which ancestor owns them, so the
     * provider tries these in order.
     */
    public static List<String> ancestorGroups(String projectPath) {
        List<String> groups = new ArrayList<>();
        if (projectPath == null || projectPath.isBlank()) {
            return groups;
        }
        String path = projectPath;
        int slash = path.lastIndexOf('/');
        while (slash > 0) {
            path = path.substring(0, slash);
            groups.add(path);
            slash = path.lastIndexOf('/');
        }
        return groups;
    }

    /** Parse the operator's optional allow-list ("acme, acme/widgets") into normalized entries. */
    public static Set<String> parseProjectAllowList(String raw) {
        Set<String> entries = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return entries;
        }
        for (String token : raw.split("[,\\s]+")) {
            String entry = token.strip().toLowerCase(Locale.ROOT);
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Whether this project is in scope. An empty allow-list accepts everything on the configured
     * host. An entry matches the project exactly, or any project beneath it as a group prefix —
     * {@code acme} covers {@code acme/tools/widgets}.
     */
    public static boolean allows(Set<String> allowList, String projectPath) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        if (projectPath == null) {
            return false;
        }
        String path = projectPath.toLowerCase(Locale.ROOT);
        for (String entry : allowList) {
            if (path.equals(entry) || path.startsWith(entry + "/")) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: Run it and confirm it passes**

```bash
./gradlew :spire-context-gitlab:test
```

Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts LICENSING.md spire-context-gitlab
git commit -m "Add the GitLab issue, merge-request and epic reference grammar"
```

---

## Task 7: GitLab HTTP client

**Files:**
- Create: `spire-context-gitlab/src/main/java/dev/codespire/context/gitlab/GitLabIssueConfig.java`
- Create: `spire-context-gitlab/src/main/java/dev/codespire/context/gitlab/GitLabIssueApiException.java`
- Create: `spire-context-gitlab/src/main/java/dev/codespire/context/gitlab/GitLabIssueClient.java`
- Test: `spire-context-gitlab/src/test/java/dev/codespire/context/gitlab/GitLabIssueClientTest.java`

**Interfaces:**
- Produces: `new GitLabIssueConfig(String baseUrl, String authKind, String secret, Set<String> projectAllowList)`; `client.getJson(String path) -> JsonNode`; `GitLabIssueApiException.status() -> int`; `static String GitLabIssueClient.encodePath(String projectPath)`.

- [ ] **Step 1: Write the failing test**

Create `spire-context-gitlab/src/test/java/dev/codespire/context/gitlab/GitLabIssueClientTest.java`:

```java
package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The HTTP layer: auth shape, nested-path encoding, status carrying, SSRF posture. */
class GitLabIssueClientTest {

    private static WireMockServer server;
    private static GitLabIssueClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = new GitLabIssueClient(
                new GitLabIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
                new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    /** A nested namespace must reach the API as one URL-encoded path segment, or it 404s. */
    @Test
    void encodesANestedProjectPathAsASingleSegment() {
        assertEquals("acme%2Ftools%2Fwidgets", GitLabIssueClient.encodePath("acme/tools/widgets"));
    }

    @Test
    void sendsTheTokenAsABearerHeader() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/acme%2Fwidgets/issues/1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"iid\":1,\"title\":\"Widget spins backwards\"}")));

        JsonNode issue = client.getJson("/api/v4/projects/acme%2Fwidgets/issues/1");

        assertEquals("Widget spins backwards", issue.path("title").asText());
        server.verify(getRequestedFor(urlPathEqualTo("/api/v4/projects/acme%2Fwidgets/issues/1"))
                .withHeader("Authorization", equalTo("Bearer TEST-token")));
    }

    @Test
    void carriesTheStatusSoTheProviderCanSkipA404ButFailOnA401() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/404")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody("{}")));
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/401")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json").withBody("{}")));

        assertEquals(404, assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/404")).status());
        assertEquals(401, assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/401")).status());
    }

    /** A 401 body can echo the token that was rejected, so it must not reach the message. */
    @Test
    void neverPutsTheUpstreamBodyIntoAnAuthFailureMessage() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/9")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"401 Unauthorized for TEST-token\"}")));

        GitLabIssueApiException thrown = assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/9"));

        assertFalse(thrown.getMessage().contains("TEST-token"));
    }

    @Test
    void reportsANonJsonSuccessAsARejectedCredential() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/2")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withBody("<html>Sign in</html>")));

        assertEquals(200, assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/2")).status());
    }

    @Test
    void refusesACrossHostRedirectIntoPrivateAddressSpace() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/3")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://127.0.0.1:9/internal")));

        assertThrows(GitLabIssueApiException.class, () -> client.getJson("/api/v4/projects/x/issues/3"));
    }

    @Test
    void rejectsAConfigWhoseAuthKindIsNotBearer() {
        assertThrows(IllegalArgumentException.class,
                () -> new GitLabIssueConfig("https://gitlab.com", "basic", "TEST-token", Set.of()));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :spire-context-gitlab:test --tests '*GitLabIssueClientTest*'
```

Expected: compile failure — `cannot find symbol: class GitLabIssueClient`.

- [ ] **Step 3: Write the config**

Create `GitLabIssueConfig.java`:

```java
package dev.codespire.context.gitlab;

import java.util.Set;

/**
 * GitLab issue-provider configuration, from the encrypted context-provider registry via the brokered
 * {@link dev.codespire.contract.context.ContextCredential} — NO defaults for credentials, fail fast
 * when unset (SECURITY.md).
 *
 * <p>{@code baseUrl} is the instance root ({@code https://gitlab.com}, or a self-managed host); the
 * client appends the {@code /api/v4/...} paths. Only {@code bearer} auth is accepted: a GitLab
 * personal access token works on the OAuth-compliant {@code Authorization} header, the same choice
 * {@code GitLabConfig} already documents for the SCM adapter.
 *
 * @param baseUrl            instance root, no {@code /api/v4} suffix
 * @param authKind           must be {@code "bearer"}
 * @param secret             personal access token
 * @param projectAllowList   optional group or {@code group/project} entries; empty = any project here
 */
public record GitLabIssueConfig(String baseUrl, String authKind, String secret,
                                Set<String> projectAllowList) {

    public GitLabIssueConfig {
        require(baseUrl, "baseUrl");
        require(authKind, "authKind");
        require(secret, "secret");
        if (!"bearer".equals(authKind)) {
            throw new IllegalArgumentException(
                    "GitLab issue context requires authKind 'bearer' (a personal access token), got '"
                            + authKind + "'.");
        }
        projectAllowList = projectAllowList == null ? Set.of() : Set.copyOf(projectAllowList);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitLab issue config '" + name + "' is required");
        }
    }
}
```

- [ ] **Step 4: Write the exception**

Create `GitLabIssueApiException.java` — identical in shape to `GitHubIssueApiException` with the message prefix changed:

```java
package dev.codespire.context.gitlab;

/**
 * Non-2xx response from the GitLab REST API. {@code status} is surfaced so the provider can skip a
 * 404 (a typo'd reference, or an epic on a non-Premium instance) while an auth failure marks the
 * whole contribution ERROR.
 *
 * <p>A 401 or 403 body can echo the token that was rejected, so {@code detail} is dropped for those
 * statuses: the message states the outcome and nothing the upstream said.
 */
public class GitLabIssueApiException extends RuntimeException {

    private final int status;

    public GitLabIssueApiException(int status, String method, String path) {
        this(status, method, path, null);
    }

    /** {@code detail} is a truncated, secret-free snippet — discarded entirely for auth statuses. */
    public GitLabIssueApiException(int status, String method, String path, String detail) {
        super("GitLab API " + method + " " + path + " failed with HTTP " + status
                + (isCredentialOutcome(status) || detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
    }

    public int status() {
        return status;
    }

    private static boolean isCredentialOutcome(int status) {
        return status == 401 || status == 403;
    }
}
```

- [ ] **Step 5: Write the client**

Add the shared transport to `spire-context-gitlab/build.gradle.kts`, beside the existing `api` line:

```kotlin
    implementation(project(":spire-http"))
```

Create `GitLabIssueClient.java`. As with GitHub, transport and the guards come from `PinnedJsonClient`
(Task 3); only GitLab's own concerns live here — plus the path encoder, because GitLab identifies a
project by its whole nested namespace:

```java
package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.http.PinnedJsonClient;
import dev.codespire.http.PinnedJsonConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Thin read-only HTTP layer over the GitLab v4 REST API.
 *
 * <p>Transport, host-pinned redirects and the private-address (SSRF) guard live in
 * {@link PinnedJsonClient}, shared with every other adapter. What stays here is GitLab's: bearer auth
 * (a personal access token works on the OAuth-compliant header), the base-URL and scope advice an
 * operator needs when the token is refused, and the project-path encoding below.
 */
public class GitLabIssueClient {

    private final PinnedJsonClient http;

    public GitLabIssueClient(GitLabIssueConfig config, ObjectMapper mapper) {
        this.http = new PinnedJsonClient(
                new PinnedJsonConfig("GitLab API", config.baseUrl(), "Bearer " + config.secret(),
                        Map.of("Accept", "application/json"),
                        "Check the base URL is the instance root (no /api/v4 suffix) and the token has "
                                + "api or read_api scope."),
                mapper, GitLabIssueApiException::new);
    }

    public JsonNode getJson(String path) {
        return http.getJson(path);
    }

    /**
     * A project path as one URL path segment. GitLab identifies a project by its full namespace path,
     * so {@code acme/tools/widgets} must arrive percent-encoded or the request resolves to a different
     * route entirely. Same approach as {@code GitLabDiffSource} in the SCM adapter.
     */
    public static String encodePath(String projectPath) {
        return URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
    }
}
```

As in Task 4, keep Step 1's redirect-refusal and non-JSON-2xx tests: they verify this adapter actually
routes through the shared guard, which no `spire-http` test can check.

- [ ] **Step 6: Run it and confirm it passes**

```bash
./gradlew :spire-context-gitlab:test
```

Expected: PASS, 19 tests (12 from Task 6 + 7 here).

- [ ] **Step 7: Commit**

```bash
git add spire-context-gitlab
git commit -m "Add the GitLab issue API client"
```

---

## Task 8: GitLab issue context provider

**Files:**
- Create: `spire-context-gitlab/src/main/java/dev/codespire/context/gitlab/GitLabIssueContextProvider.java`
- Create: `spire-context-gitlab/src/main/java/dev/codespire/context/gitlab/GitLabIssueReferenceSource.java`
- Test: `spire-context-gitlab/src/test/java/dev/codespire/context/gitlab/GitLabIssueContextProviderTest.java`

**Interfaces:**
- Consumes: `GitLabIssueRefs` (Task 6), `GitLabIssueClient`/`GitLabIssueConfig` (Task 7), `ContextRequest.scmType()` (Task 1).
- Produces: `GitLabIssueContextProvider.SOURCE == "GITLAB_ISSUES"`; `new GitLabIssueContextProvider(GitLabIssueConfig, ObjectMapper)`; `new GitLabIssueReferenceSource()`.

- [ ] **Step 1: Write the failing test**

Create `spire-context-gitlab/src/test/java/dev/codespire/context/gitlab/GitLabIssueContextProviderTest.java`:

```java
package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitLabIssueContextProvider against a WireMock GitLab (v4). */
class GitLabIssueContextProviderTest {

    private static WireMockServer server;
    private static GitLabIssueContextProvider provider;
    private static final RepoRef REPO = new RepoRef("acme/tools", "widgets");
    private static final String ENCODED = "acme%2Ftools%2Fwidgets";

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new GitLabIssueContextProvider(
                new GitLabIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
                new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private static ContextRequest request(Set<String> references, ScmType scmType) {
        return new ContextRequest("review::acme/tools/widgets#7", REPO, 7, "abc123", references,
                Set.of(), scmType);
    }

    private static void json(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void status(String path, int code) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(code)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    // ---------------------------------------------------------------- the guard

    /** The same guard as the GitHub provider, for the same reason — see the spec's hazard section. */
    @Test
    void resolvesNoBareReferenceForAReviewOnAnotherPlatform() {
        json("/api/v4/projects/" + ENCODED + "/issues/123", "{\"iid\":123,\"title\":\"Wrong platform\"}");

        assertFalse(provider.supports(request(Set.of("#123"), ScmType.GITHUB)));
        assertEquals(0, server.getAllServeEvents().size());
    }

    @Test
    void resolvesNoBareReferenceWhenThePlatformIsUnknown() {
        assertFalse(provider.supports(request(Set.of("#123"), null)));
    }

    @Test
    void stillResolvesAQualifiedReferenceForAReviewOnAnotherPlatform() {
        assertTrue(provider.supports(request(Set.of("acme/tools/widgets#12"), ScmType.GITHUB)));
    }

    // ------------------------------------------------------------- the fetching

    @Test
    void resolvesAnIssueWithItsNonSystemNotes() {
        json("/api/v4/projects/" + ENCODED + "/issues/12", """
                {"iid":12,"title":"Widget spins backwards","state":"opened",
                 "description":"Spins backwards above 40rpm.","labels":["bug"],
                 "web_url":"https://gitlab.com/acme/tools/widgets/-/issues/12"}
                """);
        json("/api/v4/projects/" + ENCODED + "/issues/12/notes", """
                [{"body":"changed title from foo to bar","system":true,"author":{"username":"bot"}},
                 {"body":"Root cause is the gear ratio.","system":false,"author":{"username":"ines"}}]
                """);

        ContextContribution contribution =
                provider.contribute(request(Set.of("#12"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        ContextItem item = contribution.items().get(0);
        assertEquals("ISSUE", item.kind());
        assertTrue(item.body().contains("Root cause is the gear ratio"));
        assertFalse(item.body().contains("changed title"), "system notes are activity noise, not context");
        assertEquals("https://gitlab.com/acme/tools/widgets/-/issues/12", item.uri());
    }

    @Test
    void resolvesAMergeRequestReferenceUnderItsOwnKind() {
        json("/api/v4/projects/" + ENCODED + "/merge_requests/34", """
                {"iid":34,"title":"Rework the gearbox","state":"merged","description":"Supersedes the ratio.",
                 "web_url":"https://gitlab.com/acme/tools/widgets/-/merge_requests/34"}
                """);
        json("/api/v4/projects/" + ENCODED + "/merge_requests/34/notes", "[]");

        ContextContribution contribution =
                provider.contribute(request(Set.of("!34"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals("PULL_REQUEST", contribution.items().get(0).kind());
    }

    /** An epic's owning group is not stated by the project path, so the nearest ancestor is tried first. */
    @Test
    void findsAnEpicOnTheNearestAncestorGroup() {
        json("/api/v4/groups/acme%2Ftools/epics/7", """
                {"iid":7,"title":"Gearbox overhaul","state":"opened","description":"Umbrella work.",
                 "web_url":"https://gitlab.com/groups/acme/tools/-/epics/7"}
                """);

        ContextContribution contribution =
                provider.contribute(request(Set.of("&7"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals("EPIC", contribution.items().get(0).kind());
    }

    @Test
    void fallsBackToTheTopLevelGroupWhenTheNearestAncestorHasNoSuchEpic() {
        status("/api/v4/groups/acme%2Ftools/epics/7", 404);
        json("/api/v4/groups/acme/epics/7", """
                {"iid":7,"title":"Gearbox overhaul","state":"opened","description":"Umbrella work."}
                """);

        ContextContribution contribution =
                provider.contribute(request(Set.of("&7"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(1, contribution.items().size());
        assertEquals("EPIC", contribution.items().get(0).kind());
    }

    /**
     * A URL names its group exactly, so it must be used as given rather than widened. Widening would
     * try the parent of the linked group first, and if that parent had an epic with the same iid — they
     * are scoped per group — the wrong epic would come back as a success with no error. Asserted on the
     * parent receiving no request at all, because a wrong-epic bug looks identical in the result.
     */
    @Test
    void usesTheGroupAnEpicUrlNamesRatherThanWideningToItsParent() {
        json("/api/v4/groups/acme%2Ftools/epics/7", """
                {"iid":7,"title":"The linked epic","state":"opened","description":"Exact group."}
                """);

        ContextContribution contribution = provider.contribute(request(
                        Set.of("https://gitlab.example.invalid/groups/acme/tools/-/epics/7"), ScmType.GITLAB))
                .toCompletableFuture().join();

        assertEquals(1, contribution.items().size());
        assertTrue(contribution.items().get(0).title().contains("The linked epic"));
        server.verify(0, getRequestedFor(urlPathEqualTo("/api/v4/groups/acme/epics/7")));
    }

    /**
     * Epics are a GitLab Premium feature, so a free-tier instance answers 403. That must cost the
     * epic only — an operator who cannot read epics must still get their issue context.
     */
    @Test
    void skipsAnEpicTheInstanceCannotServeWithoutLosingTheIssues() {
        status("/api/v4/groups/acme%2Ftools/epics/7", 403);
        status("/api/v4/groups/acme/epics/7", 403);
        json("/api/v4/projects/" + ENCODED + "/issues/12",
                "{\"iid\":12,\"title\":\"Real issue\",\"description\":\"Present.\"}");
        json("/api/v4/projects/" + ENCODED + "/issues/12/notes", "[]");

        ContextContribution contribution = provider
                .contribute(request(Set.of("&7", "#12"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(1, contribution.items().size());
        assertEquals("ISSUE", contribution.items().get(0).kind());
    }

    /** An auth failure on an issue is not the same as a Premium-gated epic: it marks everything. */
    @Test
    void reportsAnAuthFailureOnAnIssueAsAnErrorContribution() {
        status("/api/v4/projects/" + ENCODED + "/issues/12", 401);

        ContextContribution contribution =
                provider.contribute(request(Set.of("#12"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.ERROR, contribution.status());
    }

    @Test
    void skipsAMissingIssueAndKeepsTheRest() {
        status("/api/v4/projects/" + ENCODED + "/issues/999", 404);
        json("/api/v4/projects/" + ENCODED + "/issues/12",
                "{\"iid\":12,\"title\":\"Real issue\",\"description\":\"Present.\"}");
        json("/api/v4/projects/" + ENCODED + "/issues/12/notes", "[]");

        ContextContribution contribution = provider
                .contribute(request(Set.of("#12", "#999"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(1, contribution.items().size());
    }

    @Test
    void reportsItsSourceUnderTheNameTheAggregatorMerges() {
        assertEquals("GITLAB_ISSUES", provider.source());
        assertEquals("GITLAB_ISSUES", new GitLabIssueReferenceSource().source());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :spire-context-gitlab:test --tests '*GitLabIssueContextProviderTest*'
```

Expected: compile failure — `cannot find symbol: class GitLabIssueContextProvider`.

- [ ] **Step 3: Write the reference source**

Create `GitLabIssueReferenceSource.java`:

```java
package dev.codespire.context.gitlab;

import dev.codespire.contract.port.ContextReferenceSource;

import java.util.Set;

/**
 * Recognises GitLab references — {@code #12} an issue, {@code !34} a merge request, {@code &7} an
 * epic, the qualified {@code group/project#12}, and the three URL shapes — in free text.
 *
 * <p>Stateless and credential-free, so extraction can run at diff-fetch time before any credential
 * is brokered. Narrowing to this host, to the allowed projects, and to the platform the review runs
 * on happens later in {@link GitLabIssueContextProvider}.
 */
public final class GitLabIssueReferenceSource implements ContextReferenceSource {

    @Override
    public String source() {
        return GitLabIssueContextProvider.SOURCE;
    }

    @Override
    public Set<String> referencesIn(String... texts) {
        return GitLabIssueRefs.candidates(texts);
    }

    @Override
    public String normalize(String reference) {
        return GitLabIssueRefs.normalize(reference);
    }
}
```

- [ ] **Step 4: Write the provider**

Create `GitLabIssueContextProvider.java`:

```java
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
     */
    /**
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
         * three different objects that share a number. {@code pathIsAmbiguous} is deliberately NOT
         * part of it: the same epic reached by a bare reference and by its URL is one epic.
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
        for (String group : epicGroupCandidates(target)) {
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
```

- [ ] **Step 5: Run it and confirm it passes**

```bash
./gradlew :spire-context-gitlab:test
```

Expected: PASS, 34 tests.

- [ ] **Step 6: Commit**

```bash
git add spire-context-gitlab
git commit -m "Resolve GitLab issue, merge-request and epic references into context"
```

---

## Task 9: Wire the providers into the worker

**Files:**
- Modify: `spire-review-worker/build.gradle.kts:32-33`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/adapters/WorkerContextClients.java:48-58`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/adapters/WorkerContextReferences.java:25-26`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/adapters/WorkerContextReferencesTest.java` (create)

**Interfaces:**
- Consumes: both providers and both reference sources (Tasks 5, 8).
- Produces: registry types `"github-issues"` and `"gitlab-issues"` recognised by the worker.

- [ ] **Step 1: Write the failing test**

Create `spire-review-worker/src/test/java/dev/codespire/worker/adapters/WorkerContextReferencesTest.java`:

```java
package dev.codespire.worker.adapters;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition root's whole job is naming every extractor. A source whose extractor is missing
 * here contributes nothing and fails silently — the pipeline would simply never produce a candidate
 * for it, with no error anywhere. So the union is asserted directly.
 */
class WorkerContextReferencesTest {

    private final WorkerContextReferences references = new WorkerContextReferences();

    /**
     * Every source's extractor must be registered here, because a missing one contributes nothing and
     * fails silently — the pipeline would simply never produce a candidate for it, with no error.
     *
     * <p>This asserts on the registry rather than on the union of what the extractors produce, because
     * the union cannot discriminate between them: GitLab's patterns are supersets of GitHub's (its
     * qualified form takes one *or more* slashes, so it also matches {@code acme/widgets#56}), and the
     * Confluence extractor emits every {@code https://} URL it sees. So no input string is produced by
     * only one source, and a union assertion would still pass with an extractor dropped.
     */
    @Test
    void registersEverySourcesExtractor() {
        assertEquals(
                Set.of("JIRA", "CONFLUENCE", "GITHUB_ISSUES", "GITLAB_ISSUES"),
                references.registeredSources());
    }

    /** And the union still has to actually work across the syntaxes those extractors own. */
    @Test
    void unionsEveryRegisteredSourcesReferencesFromOneText() {
        Set<String> found = references.referencesIn(
                "PROJ-12 fixes #34 and acme/widgets#56, see https://acme.atlassian.net/wiki/pages/78/Spec "
                        + "plus !90 and &11");

        assertTrue(found.contains("PROJ-12"), "Jira keys");
        assertTrue(found.contains("#34"), "a bare issue reference");
        assertTrue(found.contains("acme/widgets#56"), "a qualified issue reference");
        assertTrue(found.stream().anyMatch(r -> r.contains("/pages/78/")), "Confluence links");
        assertTrue(found.contains("!90"), "a GitLab merge request");
        assertTrue(found.contains("&11"), "a GitLab epic");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :spire-review-worker:test --tests '*WorkerContextReferencesTest*'
```

Expected: FAIL — `registeredSources()` does not compile yet, and `!90`/`&11` are absent because no extractor claims them.

- [ ] **Step 3: Add the module dependencies**

In `spire-review-worker/build.gradle.kts`, after the existing context lines:

```kotlin
    implementation(project(":spire-context-github"))
    implementation(project(":spire-context-gitlab"))
```

- [ ] **Step 4: Register the extractors**

In `WorkerContextReferences.java`, add the imports and extend the list:

```java
import dev.codespire.context.github.GitHubIssueReferenceSource;
import dev.codespire.context.gitlab.GitLabIssueReferenceSource;
```

```java
    private final List<ContextReferenceSource> extractors =
            List.of(new JiraReferenceSource(), new ConfluenceReferenceSource(),
                    new GitHubIssueReferenceSource(), new GitLabIssueReferenceSource());
```

Then add this accessor beside `referencesIn`, so the registry itself is assertable:

```java
    /**
     * The {@code source()} of every registered extractor.
     *
     * <p>Exists so a test can assert this root's contents directly. The union of extracted references
     * cannot do that job: no input string is produced by only one source, because GitLab's patterns are
     * supersets of GitHub's — its qualified form takes one *or more* slashes, so it also matches
     * {@code acme/widgets#56} — and the Confluence extractor emits every {@code https://} URL it sees.
     * Package-private, so it adds nothing to the class's public surface.
     */
    Set<String> registeredSources() {
        Set<String> sources = new LinkedHashSet<>();
        for (ContextReferenceSource extractor : extractors) {
            sources.add(extractor.source());
        }
        return sources;
    }
```

- [ ] **Step 5: Run it and confirm it passes**

```bash
./gradlew :spire-review-worker:test --tests '*WorkerContextReferencesTest*'
```

Expected: PASS.

- [ ] **Step 6: Add the provider cases**

In `WorkerContextClients.java`, add the imports:

```java
import dev.codespire.context.github.GitHubIssueConfig;
import dev.codespire.context.github.GitHubIssueContextProvider;
import dev.codespire.context.github.GitHubIssueRefs;
import dev.codespire.context.gitlab.GitLabIssueConfig;
import dev.codespire.context.gitlab.GitLabIssueContextProvider;
import dev.codespire.context.gitlab.GitLabIssueRefs;
```

Extend the switch:

```java
                case "github-issues" ->
                        providers.add(new GitHubIssueContextProvider(gitHubIssueConfig(cred), mapper));
                case "gitlab-issues" ->
                        providers.add(new GitLabIssueContextProvider(gitLabIssueConfig(cred), mapper));
```

And add the two factories beside the existing ones:

```java
    private static GitHubIssueConfig gitHubIssueConfig(ContextCredential cred) {
        // projectKeys carries the optional owner/repo allow-list (same generic registry column).
        return new GitHubIssueConfig(cred.baseUrl(), cred.authKind(), cred.secret(),
                GitHubIssueRefs.parseRepoAllowList(cred.projectKeys()));
    }

    private static GitLabIssueConfig gitLabIssueConfig(ContextCredential cred) {
        // projectKeys carries the optional group/project allow-list (same generic registry column).
        return new GitLabIssueConfig(cred.baseUrl(), cred.authKind(), cred.secret(),
                GitLabIssueRefs.parseProjectAllowList(cred.projectKeys()));
    }
```

- [ ] **Step 7: Run the worker's tests and the neutrality check**

```bash
./gradlew :spire-review-worker:test :spire-arch:test
```

Expected: PASS. Both modified files are already on the ALLOWED list; no new entry.

- [ ] **Step 8: Commit**

```bash
git add spire-review-worker
git commit -m "Register the issue context providers with the worker"
```

---

## Task 10: Registry surface — check and preview

**Files:**
- Modify: `spire-orchestrator/build.gradle.kts`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextKeyValidator.java:107-111`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextProviderResource.java:43,160-165`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/context/ContextProviderResourceTest.java` (extend — REST level, not a new grammar-only test)

**Interfaces:**
- Consumes: both providers and both `*Refs` (Tasks 2, 5, 6, 8).
- Produces: `/api/context-providers` accepts the two new types for save, `/{id}/check` and `/{id}/preview`.

- [ ] **Step 1: Write the failing test**

Add REST-level coverage to the **existing** `spire-orchestrator/src/test/java/dev/codespire/orchestrator/context/ContextProviderResourceTest.java`, following that suite's own pattern (`previewResolvesABareNumberViaProjectKeysAndReturnsTheItem`, `previewIsEmptyForABareNumberWithoutProjectKeys`): create the provider via `POST /api/context-providers`, then `POST /api/context-providers/{id}/preview`.

**Do not create a separate `IssueContextPreviewTest` that calls the grammars directly.** An earlier draft of this plan did, and it was worthless: two of its three tests duplicated assertions already in `GitHubIssueRefsTest`, and the third asserted the guidance constant's *content* without ever showing it was *returned*. None of them touched `ContextProviderResource`, so the preview branching this task ships was untested while the suite stayed green. If a test does not import or invoke the class under test, it is not covering it.

Five behaviours, all at REST level:

1. **`basic` is refused on save for both new types** — the spec requires rejection at save, and the credential ping can otherwise *succeed*, because GitHub accepts a PAT as a Basic-auth password:

```java
    /**
     * The spec requires basic auth to be refused when the row is SAVED, not merely when the worker
     * later builds a client from it. Without this the operator gets no feedback at all: the save
     * succeeds — the ping can even pass — and the failure surfaces later as a broken context step or
     * a raw 500 from preview.
     */
    @Test
    void refusesBasicAuthForTheIssueTypes() {
        for (String type : java.util.List.of("github-issues", "gitlab-issues")) {
            var b = body("TEST-token");
            b.put("type", type);
            b.put("authKind", "basic");
            given().contentType("application/json").body(b)
                    .when().post("/api/context-providers").then().statusCode(400);
        }
    }
```

2. **A bare reference previews as `EMPTY` with a non-null `detail`** — for each of the two types. This is what proves `BARE_REFERENCE_GUIDANCE` is actually returned under the bare-only condition.

3. **A qualified reference or URL previews an item** — for each of the two types, the happy path through the new branch.

Behaviours 2 and 3 need a WireMock stub per type for the save-time credential ping and for the issue fetch. **Read `ContextKeyValidator.accountFrom(...)` to learn which JSON field the ping requires** rather than guessing, and stand up `github`/`gitlab` WireMock servers alongside the existing `jira` one following that suite's existing `@BeforeAll` setup. Use `TEST-token` secrets and `example.invalid` hosts.
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :spire-orchestrator:test --tests '*ContextProviderResourceTest*'
```

Expected: FAIL — `refusesBasicAuthForTheIssueTypes` gets 201 instead of 400 (no per-type rule yet), and the preview tests get 400 on save because `TYPES` does not accept the new types.

- [ ] **Step 3: Add the module dependencies**

In `spire-orchestrator/build.gradle.kts`, beside the existing `spire-context-jira` / `spire-context-confluence` lines:

```kotlin
    implementation(project(":spire-context-github"))
    implementation(project(":spire-context-gitlab"))
```

- [ ] **Step 4: Add the connectivity-check paths**

In `ContextKeyValidator.java`, extend the `whoAmI` switch:

```java
        String whoAmI = switch (type) {
            case "jira" -> "/rest/api/2/myself";
            case "confluence" -> "/rest/api/user/current";
            case "github-issues" -> "/user";
            case "gitlab-issues" -> "/api/v4/user";
            default -> throw new BadRequestException("Unsupported context provider type '" + type + "'");
        };
```

- [ ] **Step 5: Accept the types and add the preview branches**

In `ContextProviderResource.java`, widen `TYPES` (line 43):

```java
    private static final Set<String> TYPES =
            Set.of("jira", "confluence", "github-issues", "gitlab-issues");
```

Add the bearer-only rule beside `TYPES`. The spec requires `basic` to be **rejected on save** — the
`*Config` constructors also reject it, but they run in the worker at review time, so without this an
operator can save a row the worker must then refuse, and the context step fails with no UI feedback:

```java
    /**
     * Types whose API accepts only a bearer token. GitHub's basic auth is deprecated and a GitLab
     * personal access token works on the OAuth-compliant {@code Authorization} header, so accepting
     * {@code basic} here would only let an operator save something the worker has to refuse later —
     * failing the context step with nothing on screen to explain why.
     */
    private static final Set<String> BEARER_ONLY_TYPES = Set.of("github-issues", "gitlab-issues");
```

and enforce it in the private `validate(ContextProviderInput in, boolean creating)`, immediately after
the existing `AUTH_KINDS` check and **before** the "basic needs a username" check, so an unsupported
combination is not first asked for a username it cannot use:

```java
        if (BEARER_ONLY_TYPES.contains(in.type()) && !"bearer".equals(in.authKind())) {
            throw new BadRequestException("Context provider type '" + in.type()
                    + "' requires authKind 'bearer' (a personal access token). Basic auth is not "
                    + "supported for this type.");
        }
```

Cover it in `ContextProviderResourceTest`, following that suite's existing REST-level pattern (see
`rejectsAnUnsupportedType` and `basicAuthRequiresAUsername`) rather than reaching for the private
method:

```java
    /**
     * The spec requires basic auth to be refused when the row is SAVED, not merely when the worker
     * later builds a client from it. Without this the operator gets no feedback at all: the save
     * succeeds and the failure surfaces as a broken context step during a review.
     */
    @Test
    void refusesBasicAuthForTheIssueTypes() {
        for (String type : List.of("github-issues", "gitlab-issues")) {
            var b = body("TEST-token");
            b.put("type", type);
            b.put("authKind", "basic");
            given().contentType("application/json").body(b)
                    .when().post("/api/context-providers").then().statusCode(400);
        }
    }
```

Add the shared guidance constant beside it:

```java
    /**
     * Preview resolves one reference with no pull request behind it, so a bare {@code #123} has no
     * repository to belong to. Saying which two inputs DO work turns a dead end into a next step.
     */
    static final String BARE_REFERENCE_GUIDANCE =
            "A bare #123 needs a repository — enter the qualified form (owner/repo#123) or paste the "
                    + "issue URL.";
```

Extend the preview switch:

```java
        return switch (cfg.type()) {
            case "jira" -> previewJira(cfg, body.text());
            case "confluence" -> previewConfluence(cfg, body.text());
            case "github-issues" -> previewGitHubIssues(cfg, body.text());
            case "gitlab-issues" -> previewGitLabIssues(cfg, body.text());
            default -> throw new BadRequestException("Preview is not supported for type '" + cfg.type() + "'");
        };
```

Add the two methods beside `previewConfluence`, with the imports they need
(`dev.codespire.context.github.*`, `dev.codespire.context.gitlab.*`, `dev.codespire.contract.port.ScmType`):

```java
    private PreviewResult previewGitHubIssues(ContextProviderConfig cfg, String text) {
        Set<String> references = GitHubIssueRefs.candidates(text);
        boolean anyQualified = references.stream()
                .map(GitHubIssueRefs::parse)
                .flatMap(java.util.Optional::stream)
                .anyMatch(ref -> !ref.isRepoRelative());
        if (!anyQualified) {
            return new PreviewResult(List.of(), "EMPTY", List.of(),
                    references.isEmpty()
                            ? "No issue reference found in the input. Enter owner/repo#123 or paste an issue URL."
                            : BARE_REFERENCE_GUIDANCE);
        }
        ContextProvider provider = new GitHubIssueContextProvider(
                new GitHubIssueConfig(cfg.baseUrl(), cfg.authKind(), cfg.secret(),
                        GitHubIssueRefs.parseRepoAllowList(cfg.projectKeys())), mapper);
        // The operator is explicitly testing THIS provider, so the request states its own platform —
        // a preview with a null platform would make every repo-relative reference decline.
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                references, Set.of(), ScmType.GITHUB);
        return runPreview(cfg, provider, req, List.copyOf(references),
                "GitHub did not return the issue as JSON — run the connection check; the token is likely "
                        + "being redirected to a sign-in page (wrong base URL, or the token cannot read issues).",
                "Could not reach GitHub to resolve the reference(s).");
    }

    private PreviewResult previewGitLabIssues(ContextProviderConfig cfg, String text) {
        Set<String> references = GitLabIssueRefs.candidates(text);
        boolean anyQualified = references.stream()
                .map(GitLabIssueRefs::parse)
                .flatMap(java.util.Optional::stream)
                .anyMatch(ref -> !ref.isProjectRelative());
        if (!anyQualified) {
            return new PreviewResult(List.of(), "EMPTY", List.of(),
                    references.isEmpty()
                            ? "No reference found in the input. Enter group/project#123 or paste an issue URL."
                            : BARE_REFERENCE_GUIDANCE);
        }
        ContextProvider provider = new GitLabIssueContextProvider(
                new GitLabIssueConfig(cfg.baseUrl(), cfg.authKind(), cfg.secret(),
                        GitLabIssueRefs.parseProjectAllowList(cfg.projectKeys())), mapper);
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                references, Set.of(), ScmType.GITLAB);
        return runPreview(cfg, provider, req, List.copyOf(references),
                "GitLab did not return the issue as JSON — run the connection check; the token is likely "
                        + "being redirected to a sign-in page (wrong base URL, or the token lacks read_api).",
                "Could not reach GitLab to resolve the reference(s).");
    }
```

Replace the `null` placeholders Task 1 Step 9 left in `previewJira`/`previewConfluence` with `null` kept deliberately — neither resolves a repo-relative reference, so the platform is genuinely irrelevant there. Add a comment saying so:

```java
        // Jira keys are globally unique within a site, so the review's platform is irrelevant here.
        ContextRequest req = new ContextRequest("preview", new RepoRef("preview", "preview"), 0, "",
                keys, Set.of(), null);
```

- [ ] **Step 6: Run it and confirm it passes**

```bash
./gradlew :spire-orchestrator:test --tests '*ContextProviderResourceTest*'
```

Expected: PASS — the five new cases plus the suite's existing tests.

- [ ] **Step 7: Run the orchestrator suite and the neutrality check**

```bash
./gradlew :spire-orchestrator:test :spire-arch:test
```

Expected: PASS. Both modified files are already allowlisted.

- [ ] **Step 8: Commit**

```bash
git add spire-orchestrator
git commit -m "Add the issue providers to the context registry surface"
```

---

## Task 11: Settings → Context UI

**Files:**
- Modify: `spire-ui/src/api.ts:584`
- Modify: `spire-ui/src/components/SettingsContextProviders.tsx:22,37-68`
- Test: `spire-ui/src/components/SettingsContextProviders.types.test.ts` (create)

**Interfaces:**
- Consumes: the registry types from Task 10.
- Produces: `ContextType` union `'jira' | 'confluence' | 'github-issues' | 'gitlab-issues'`.

- [ ] **Step 1: Write the failing test**

Create `spire-ui/src/components/SettingsContextProviders.types.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { CONTEXT_TYPES, TYPE_COPY } from './SettingsContextProviders';

/**
 * A type in the selector with no copy entry renders a form with blank labels and no hint — the
 * operator gets a field they cannot interpret. The two lists must stay in step, so assert it rather
 * than trusting review to notice.
 */
describe('context provider types', () => {
  it('offers every type the backend accepts', () => {
    expect(CONTEXT_TYPES).toEqual(['jira', 'confluence', 'github-issues', 'gitlab-issues']);
  });

  it('gives every offered type its own form copy', () => {
    for (const type of CONTEXT_TYPES) {
      const copy = TYPE_COPY[type];
      expect(copy, `missing copy for ${type}`).toBeDefined();
      expect(copy.baseUrlPlaceholder.length).toBeGreaterThan(0);
      expect(copy.narrowLabel.length).toBeGreaterThan(0);
      expect(copy.previewLabel.length).toBeGreaterThan(0);
    }
  });

  /** Preview cannot resolve a bare reference, so its placeholder must not suggest one. */
  it('asks the issue types for a qualified reference or a URL', () => {
    for (const type of ['github-issues', 'gitlab-issues'] as const) {
      const placeholder = TYPE_COPY[type].previewPlaceholder(null);
      expect(placeholder).toMatch(/#123|URL/);
      expect(placeholder.startsWith('#')).toBe(false);
    }
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd spire-ui && npx vitest run src/components/SettingsContextProviders.types.test.ts
```

Expected: FAIL — `CONTEXT_TYPES` and `TYPE_COPY` are not exported.

- [ ] **Step 3: Widen the API type**

In `spire-ui/src/api.ts`, line 584:

```ts
export type ContextType = 'jira' | 'confluence' | 'github-issues' | 'gitlab-issues';
```

- [ ] **Step 4: Export and extend the UI lists**

In `SettingsContextProviders.tsx`, export both constants and add the entries:

```tsx
export const CONTEXT_TYPES: ContextType[] = ['jira', 'confluence', 'github-issues', 'gitlab-issues'];
```

```tsx
export const TYPE_COPY: Record<ContextType, TypeCopy> = {
```

Add after the `confluence` entry:

```tsx
  'github-issues': {
    namePlaceholder: 'Acme GitHub issues',
    baseUrlPlaceholder: 'https://api.github.com',
    baseUrlHint:
      'The API root — https://api.github.com for github.com, or https://your-host/api/v3 for ' +
      'Enterprise Server. Needs a token that can read issues.',
    narrowLabel: 'Owner/repo allow-list',
    narrowPlaceholder: 'acme, acme/widgets',
    narrowHint:
      'Optional: only these repositories are looked up. An owner (acme) covers everything under it; ' +
      'acme/widgets matches one repository. Leave blank to accept any repository on this host.',
    previewLabel: 'Issue reference',
    previewPlaceholder: () => 'a qualified reference (acme/widgets#123) or an issue URL',
    previewHint:
      'A bare #123 only means something inside a pull request, so the test box needs the repository ' +
      'named — in the reference or in a pasted URL.',
  },
  'gitlab-issues': {
    namePlaceholder: 'Acme GitLab issues',
    baseUrlPlaceholder: 'https://gitlab.com',
    baseUrlHint:
      'Your instance root, with no /api/v4 suffix — the client appends the API paths. Needs a token ' +
      'with read_api scope.',
    narrowLabel: 'Group/project allow-list',
    narrowPlaceholder: 'acme, acme/tools/widgets',
    narrowHint:
      'Optional: only these projects are looked up. A group (acme) covers every project beneath it. ' +
      'Leave blank to accept any project on this host.',
    previewLabel: 'Issue reference',
    previewPlaceholder: () => 'a qualified reference (acme/widgets#123) or an issue URL',
    previewHint:
      'Resolves issues (#12), merge requests (!34) and epics (&7). A bare reference needs the project ' +
      'named here, since the test box has no merge request behind it.',
  },
```

- [ ] **Step 5: Run it and confirm it passes**

```bash
cd spire-ui && npx vitest run src/components/SettingsContextProviders.types.test.ts
```

Expected: PASS, 3 tests.

- [ ] **Step 6: Run the whole UI suite and the type check**

```bash
cd spire-ui && npm test && npx tsc --noEmit
```

Expected: all suites pass; `tsc` silent. If a `Record<ContextType, …>` elsewhere now misses a key, add the entry — do not widen the type to `Partial`.

- [ ] **Step 7: Commit**

```bash
git add spire-ui
git commit -m "Offer the issue context providers in Settings"
```

---

## Task 12: Documentation and the live verification pass

**Files:**
- Modify: `docs/SMOKE-TEST.md` (new mode section at the end)
- Modify: `docs/ROADMAP.md` (E14 → done; remove it from the What-is-left table)
- Modify: `CLAUDE.md` (status bullet)
- Modify: `spire-contract/src/main/java/dev/codespire/contract/review/ContextItem.java` (kind list)

No techdebt entry: the duplicated HTTP client this task would once have recorded was extracted into
`spire-http` in Task 3 instead. Mention `spire-http` in the CLAUDE.md status bullet — a fifth
Apache-2.0 module is a structural change a future reader needs to know about.

- [ ] **Step 1: Extend the ContextItem kind list**

The javadoc is the only inventory of kinds. Comments are exempt from the neutrality check, and these names are neutral anyway:

```java
/** kind: JIRA_TICKET | CONFLUENCE_PAGE | ISSUE | PULL_REQUEST | EPIC | RULE | CODE_SNIPPET | MEMORY_NOTE. */
public record ContextItem(String kind, String title, String body, String uri) {
}
```

- [ ] **Step 2: Full build and UI check**

```bash
./gradlew build && cd spire-ui && npm test && npx tsc --noEmit && npm audit
```

Expected: BUILD SUCCESSFUL; UI suites pass; `tsc` silent. `npm audit` findings are pre-existing — see `techdebt/spire-ui/3-2-npm-audit-flags-postcss-and-react-router.md`; do not fix them here.

Prove the Java run was real:

```bash
find . -name "TEST-*.xml" -newermt "-15 minutes" | wc -l
```

Record the count and the total test number in the commit body.

- [ ] **Step 3: Write the runbook mode**

Append to `docs/SMOKE-TEST.md`:

````markdown
## Mode H — context provisioning across every provider type

Proves that automated context provisioning works during a real review and that the retrieved text
reaches the model, for all four provider types. Code-complete plus WireMock is not the bar: a blob
that assembles and a prompt that omits it look identical from the dashboard.

**Setup.** In Settings → Context register one provider per type you can reach, each with its own
token (Jira/Confluence: existing rows; GitHub Issues: `https://api.github.com` + a PAT that can read
issues; GitLab Issues: `https://gitlab.com` + a PAT with `read_api`). Run each row's **Check** —
all must show the token owner. Run each row's **Test** with a qualified reference or a URL.

**Per provider type:**

1. Open a real PR/MR whose title or description references a real issue in that system — a Jira key,
   a Confluence page URL, `#123` (bare, in the review's own repository), or `owner/repo#123`.
2. On the review's detail page, confirm the timeline shows `ContextRequested`, then
   `ContextContributed` naming the source with status `OK` and a non-zero item count, then
   `ContextAssembled`.
3. **Confirm the text reached the prompt, not just the blob.** Open the review's LLM call record and
   find the retrieved title inside the fenced `{context}` slot of the prompt. This is the step that
   distinguishes "assembled" from "sent"; do not skip it and infer from the timeline.
4. Note whether the review's output shows awareness of the context. This is a weak signal — record
   it as an observation, never as proof.

**Negative pass — the cross-platform guard.** With a GitHub Issues provider enabled, run a review on
a **GitLab or Bitbucket** PR whose description contains a bare `#123` that exists in a same-named
repository on GitHub. Expect: `GITHUB_ISSUES` does not appear as a contributing source, and no issue
text appears in the prompt. A resolved issue here is the cross-wiring defect the `ScmType` guard
exists to prevent — stop and report it.

**Real data only.** Use real issues in real repositories. Do not create issues whose content exists
only to make this pass.
````

- [ ] **Step 4: Update the roadmap and status**

In `docs/ROADMAP.md`, mark item 14 done, dated, with what shipped; delete its **E14** row from the
*What is actually left* table. In `CLAUDE.md`, add a status bullet in the established voice covering:
the two modules, the `ScmType` guard and why a repo-relative reference needs it, the reference forms,
kinds `ISSUE`/`PULL_REQUEST`/`EPIC`, epics degrading on non-Premium instances, and the test total.

- [ ] **Step 5: Commit the documentation**

```bash
git add docs CLAUDE.md techdebt spire-contract
git commit -m "Document the issue context providers and how to verify them"
```

- [ ] **Step 6: Run the live pass**

Execute Mode H above, including the negative pass. Record the outcome per provider type. **A defect
found here is fixed with a test before this plan is complete** — that is what the live pass is for.
Report honestly: if a provider type could not be reached (no instance, no token), say so and name it
rather than reporting a pass.

---

## Self-Review

**Spec coverage**

| Spec requirement | Task |
|---|---|
| Two Apache-2.0 modules, framework-free, SSRF-guarded | 3 (shared client), 2, 4 (GitHub); 6, 7 (GitLab) |
| One home for the SSRF/redirect guard | 3 (`spire-http`, Jira + Confluence migrated onto it) |
| `source()` `GITHUB_ISSUES` / `GITLAB_ISSUES`, types `github-issues` / `gitlab-issues` | 5, 8, 9 |
| `ScmType` on `GatherContext`/`ContextRequest`, from `ReviewProviderResolver`, failing closed | 1 |
| No upcaster needed | 1 (verified during design; nothing to do) |
| Owner/repo allow-list reusing `projectKeys`, no migration | 2, 6, 9 |
| All reference forms: bare, qualified, URL, `!` MR, `&` epic | 2, 6 |
| PR/MR resolved and labelled, not filtered | 5, 8 |
| Cross-form duplicates handled by the existing `uri()` dedup | 5, 8 (per-provider `key()` dedup) + existing `ContextWorker` |
| Own token in the context registry | 4, 7 (config records) |
| `basic` rejected **on save** | 10 (`BEARER_ONLY_TYPES` in `validate`). Note the *where*: tasks 4 and 7 also reject it, but in the worker at review time — that does not satisfy "on save", and mapping this row to them was how the requirement first reached no task at all. |
| Comments/notes, last 5, 500 chars; description clipped at 4,000 | 5, 8 |
| `MAX_REFERENCES` = 10 | 2, 5, 6, 8 |
| 404 skip; 401/403 ERROR; epic 403/404 skips only that reference | 5, 8 |
| Epic group derivation, nearest ancestor then top-level | 6 (`ancestorGroups`), 8 (`fetchEpic`) |
| Check paths `/user`, `/api/v4/user` | 10 |
| Preview rejects a bare reference with actionable guidance | 10 |
| UI selector with type-aware copy | 11 |
| Grammar negatives, WireMock per adapter, cross-wire test | 2, 3, 4, 5, 6, 7, 8 |
| `spire-arch` green, no new allowlist entries | 1, 9, 10 |
| Live pass across every type + negative pass | 12 |
| No new user-visible working-name occurrences | Global Constraints |

**Gap found and closed:** the spec says "a repo-relative provider's `supports()` returns false unless
it matches its own axis", which reads as gating the whole provider. Tasks 5 and 8 gate the
*reference*: a bare one needs the platform, a qualified one does not, because it names its own
repository. Both provider tests assert the distinction
(`stillResolvesAQualifiedReferenceForAReviewOnAnotherPlatform`). **The spec has been corrected** to
state the gate as per-reference, so the two documents agree.

**Type consistency:** `GitHubIssueRefs.Ref.isRepoRelative()` vs `GitLabIssueRefs.Ref.isProjectRelative()`
— deliberately different, matching each platform's own vocabulary (repository / project), and each is
used only within its own module plus Task 10's preview, which references both correctly.
`GitHubIssueRefs.parseRepoAllowList` / `GitLabIssueRefs.parseProjectAllowList` likewise differ by
platform vocabulary; Task 9 calls each from its own factory. `MAX_REFERENCES` (provider) and
`MAX_REFS` (grammar) are separate constants in separate classes, both 10, capping different stages —
extraction and resolution.

**Placeholder scan:** clean. Every code step carries the code; every command carries its expected
outcome.

**Amendment (2026-07-30, before execution):** the plan originally had each adapter carry its own copy
of the pinned-redirect HTTP client — four near-identical copies once these two landed — and recorded
that as tech debt. Ruled the other way: **Task 3 now extracts `spire-http` first** and migrates Jira
and Confluence onto it, so the SSRF and redirect guard has one home and a fix to it lands once. Tasks
4 and 7 build the two new clients on that shared base instead of copying, and the techdebt entry the
original Task 12 would have written is no longer needed. Every task from the original 3 onward is
renumbered one higher.
