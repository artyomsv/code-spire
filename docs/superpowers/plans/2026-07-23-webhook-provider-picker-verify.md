# Webhook Provider-Picker + Verify-Repo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the "Add webhook" form pick a registered SCM provider (instead of a bare type) and verify the target repository exists via that provider's token, without auto-provisioning.

**Architecture:** A new `DiffSource.assertRepoAccessible(RepoRef)` SPI (default-unsupported, overridden in the 3 adapters to GET the repo) backs a new orchestrator `POST /api/providers/{id}/verify-repo` that mirrors the existing `/check`. The UI webhook form sources its provider list from the orchestrator (`fetchProviders`) and calls verify on the orchestrator; the gateway's webhook-create payload and isolation are unchanged.

**Tech Stack:** Java 25 / Quarkus 3.36 (JAX-RS + CDI, raw JDBC — NOT Spring); WireMock + RestAssured (`@QuarkusTest`) for backend tests; React 19 / Vite / TypeScript, vitest + testing-library for UI.

## Global Constraints

- **Gateway stays isolated:** the webhook-create payload is unchanged — `createWebhookRepo({ providerType, scope, target, enabled })`; the gateway never sees a token. Verify runs entirely on the orchestrator (which holds the token).
- **No auto-provisioning**, **no org-scope repo-verify** (org relies on the existing `/api/providers/{id}/check`), **no broader token scope** (a repo GET works with the read token).
- **Verify is advisory** — a failed or un-run verify never blocks "Add webhook".
- `DiffSource.assertRepoAccessible` is a **`default` method** (throws `UnsupportedOperationException`) so `StubScm.StubDiffSource` and any other `DiffSource` impl compile unchanged. Only the 3 real adapters override it.
- Do **not** modify the gateway (`spire-gateway`), the `webhook_repo` schema, the reveal modal, or the GitHub adapter's review/conversation code.
- Java: 4-space indent, explicit types, methods ≤30 lines, records for DTOs. TS: 2-space indent, `interface` for object shapes.
- 404 on a repo GET can mean "private repo the token can't see," not only "missing" — word the detail accordingly.

---

## File Structure

**Backend:**
- `spire-contract/.../port/DiffSource.java` — add the `assertRepoAccessible` default.
- `spire-scm-github/.../GitHubDiffSource.java`, `spire-scm-gitlab/.../GitLabDiffSource.java`, `spire-scm-bitbucket/.../BitbucketCloudDiffSource.java` — override it.
- `spire-orchestrator/.../provider/ProviderResource.java` — new endpoint + records + `reasonWithNotFound` refactor + inject `ProviderClients`.
- Tests: `GitHubApiTest`, `GitLabApiTest`, `BitbucketCloudApiTest` (adapter), `ProviderResourceTest` (endpoint).

**UI:**
- `spire-ui/src/api.ts` — `RepoCheck` + `verifyRepo`.
- `spire-ui/src/components/SettingsWebhookRepos.tsx` — form refactor (picker + owner + slug + verify).
- `spire-ui/src/index.css` — `.wh-repo-input` / `.wh-owner` / `.wh-verify`.
- Tests: `spire-ui/src/components/SettingsWebhookRepos.form.test.tsx` (new render test).

---

## Task 1: `DiffSource.assertRepoAccessible` SPI + 3 adapter overrides

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/port/DiffSource.java`
- Modify: `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubDiffSource.java`
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabDiffSource.java`
- Modify: `spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudDiffSource.java`
- Test: `spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubApiTest.java`, `spire-scm-gitlab/.../GitLabApiTest.java`, `spire-scm-bitbucket/.../BitbucketCloudApiTest.java`

**Interfaces:**
- Produces: `void DiffSource.assertRepoAccessible(RepoRef repo)` — GETs the repo resource; throws the adapter's `ScmApiException` on non-2xx (404 = missing/not-visible, 401/403 = no access). Default throws `UnsupportedOperationException`. Consumed by Task 2.

- [ ] **Step 1: Write the failing adapter tests**

Each `*ApiTest` already builds a `DiffSource` (field `diffSource`) against a `WireMockServer` (field `server`) with the module's config — reuse that harness. Add:

GitHub (`GitHubApiTest.java`) — GitHub GETs `/repos/{owner}/{slug}`:
```java
    @Test
    void assertRepoAccessibleReturnsOnA200() {
        server.stubFor(get(urlEqualTo("/repos/acme/widgets")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{ \"full_name\": \"acme/widgets\" }")));
        diffSource.assertRepoAccessible(new RepoRef("acme", "widgets")); // no throw
    }

    @Test
    void assertRepoAccessibleThrowsNotFoundOn404() {
        server.stubFor(get(urlEqualTo("/repos/acme/ghost")).willReturn(aResponse().withStatus(404)));
        GitHubApiException e = assertThrows(GitHubApiException.class,
                () -> diffSource.assertRepoAccessible(new RepoRef("acme", "ghost")));
        assertTrue(e.isNotFound());
    }
```

GitLab (`GitLabApiTest.java`) — GitLab GETs `/projects/{urlencoded owner/slug}`:
```java
    @Test
    void assertRepoAccessibleReturnsOnA200() {
        server.stubFor(get(urlEqualTo("/projects/acme%2Fwidgets")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{ \"id\": 1 }")));
        diffSource.assertRepoAccessible(new RepoRef("acme", "widgets"));
    }

    @Test
    void assertRepoAccessibleThrowsNotFoundOn404() {
        server.stubFor(get(urlEqualTo("/projects/acme%2Fghost")).willReturn(aResponse().withStatus(404)));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> diffSource.assertRepoAccessible(new RepoRef("acme", "ghost")));
        assertTrue(e.isNotFound());
    }
```

Bitbucket (`BitbucketCloudApiTest.java`) — Bitbucket GETs `/repositories/{owner}/{slug}`:
```java
    @Test
    void assertRepoAccessibleReturnsOnA200() {
        server.stubFor(get(urlEqualTo("/repositories/acme/widgets")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{ \"full_name\": \"acme/widgets\" }")));
        diffSource.assertRepoAccessible(new RepoRef("acme", "widgets"));
    }

    @Test
    void assertRepoAccessibleThrowsNotFoundOn404() {
        server.stubFor(get(urlEqualTo("/repositories/acme/ghost")).willReturn(aResponse().withStatus(404)));
        BitbucketApiException e = assertThrows(BitbucketApiException.class,
                () -> diffSource.assertRepoAccessible(new RepoRef("acme", "ghost")));
        assertTrue(e.isNotFound());
    }
```

If a `*ApiTest` uses different field names for the WireMock server / diff source, match the file's existing names. Ensure `assertThrows`/`assertTrue` and `RepoRef` are imported (mirror the file's existing imports).

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-scm-github:test --tests "*GitHubApiTest*" :spire-scm-gitlab:test --tests "*GitLabApiTest*" :spire-scm-bitbucket:test --tests "*BitbucketCloudApiTest*"`
Expected: FAIL — `assertRepoAccessible` resolves to the default and throws `UnsupportedOperationException` (not the stubbed 200/404), so the tests error.

- [ ] **Step 3: Add the SPI default**

In `DiffSource.java`, add after `fetchCompareDiff`:
```java
    /**
     * Confirm the repository exists and is reachable with the configured token. Implementations GET the
     * repo resource; a non-2xx surfaces as the adapter's {@code ScmApiException} (404 = missing or not
     * visible to the token, 401/403 = no access), which the caller classifies. Default is unsupported so
     * stub and other DiffSource impls are unaffected — only the real SCM adapters override it.
     */
    default void assertRepoAccessible(RepoRef repo) {
        throw new UnsupportedOperationException(type() + " cannot verify a repository");
    }
```

- [ ] **Step 4: Override in the three adapters**

`GitHubDiffSource.java` (after `whoami()`):
```java
    @Override
    public void assertRepoAccessible(RepoRef repo) {
        client.getJson("/repos/" + repo.full());
    }
```

`GitLabDiffSource.java` (after `whoami()`; reuses the existing `private static String encodedProject(RepoRef)`):
```java
    @Override
    public void assertRepoAccessible(RepoRef repo) {
        client.getJson("/projects/" + encodedProject(repo));
    }
```

`BitbucketCloudDiffSource.java` (after `assertWorkspaceAccess`):
```java
    @Override
    public void assertRepoAccessible(RepoRef repo) {
        client.getJson("/repositories/" + repo.full());
    }
```

- [ ] **Step 5: Run the three suites — verify pass**

Run: `./gradlew :spire-scm-github:test :spire-scm-gitlab:test :spire-scm-bitbucket:test`
Expected: PASS (new tests + no regressions). (`:spire-orchestrator:quarkusAppPartsBuild` is a KNOWN pre-existing toolchain error — use module-scoped `test`, not a full `build`.)

- [ ] **Step 6: Commit**

```bash
git add spire-contract/src/main/java/dev/codespire/contract/port/DiffSource.java \
        spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubDiffSource.java \
        spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabDiffSource.java \
        spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudDiffSource.java \
        spire-scm-github/src/test/java/dev/codespire/scm/github/GitHubApiTest.java \
        spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabApiTest.java \
        spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudApiTest.java
git commit -m "Add DiffSource.assertRepoAccessible with per-adapter repo GETs"
```

---

## Task 2: Orchestrator `POST /api/providers/{id}/verify-repo`

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderResourceTest.java`

**Interfaces:**
- Consumes: `DiffSource.assertRepoAccessible(RepoRef)` (Task 1); `ProviderClients.diffSource(ScmProvider)` (same package); `registry.resolveById(UUID)`; the existing `reason(...)` classifier.
- Produces: `POST /api/providers/{id}/verify-repo` body `{"repo":"owner/repo"}` → `record RepoCheck(boolean ok, String detail)` (200; `400` for a blank/no-slash repo; `404` for an unknown provider id). Consumed by Task 4 (UI).

**Context:** `ProviderResource` is JAX-RS (`@Path("/api/providers")`), injects `ProviderRegistry registry` + `ProviderIdentityResolver identity`. `ProviderClients` is in the same package. Mirror the existing `POST /{id}/check` (`ProviderResource.java:110-123`) and `reason()` (`:130-145`).

- [ ] **Step 1: Write the failing REST tests**

Add to `ProviderResourceTest.java` (a `@QuarkusTest` with a WireMock `scm`; `body(...)` creates a `bitbucket-cloud` provider whose baseUrl points at `scm`; `@BeforeEach` stubs `/user` for the create-time whoami). Bitbucket's repo GET is `/repositories/{owner}/{slug}`:
```java
    @Test
    void verifyRepoReportsOkWhenRepoExists() {
        String id = given().contentType("application/json").body(body("rest-verify-ok", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        scm.stubFor(get(urlEqualTo("/repositories/rest-verify-ok/widgets")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{ \"full_name\": \"rest-verify-ok/widgets\" }")));
        given().contentType("application/json").body(Map.of("repo", "rest-verify-ok/widgets"))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(200).body("ok", is(true));
    }

    @Test
    void verifyRepoReportsNotFound() {
        String id = given().contentType("application/json").body(body("rest-verify-404", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        scm.stubFor(get(urlEqualTo("/repositories/rest-verify-404/ghost")).willReturn(aResponse().withStatus(404)));
        given().contentType("application/json").body(Map.of("repo", "rest-verify-404/ghost"))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", org.hamcrest.Matchers.containsString("not found"));
    }

    @Test
    void verifyRepoReportsUnauthorized() {
        String id = given().contentType("application/json").body(body("rest-verify-401", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        scm.stubFor(get(urlEqualTo("/repositories/rest-verify-401/secret")).willReturn(aResponse().withStatus(403)));
        given().contentType("application/json").body(Map.of("repo", "rest-verify-401/secret"))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", org.hamcrest.Matchers.containsString("Authentication failed"));
    }

    @Test
    void verifyRepoRejectsABlankRepo() {
        String id = given().contentType("application/json").body(body("rest-verify-blank", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(Map.of("repo", ""))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(400);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ProviderResourceTest*"`
Expected: FAIL — `verify-repo` returns 404 (no such endpoint) instead of 200/400.

- [ ] **Step 3: Implement the endpoint**

In `ProviderResource.java`: add the import and inject `ProviderClients`:
```java
import dev.codespire.contract.scm.RepoRef;
```
```java
    @Inject
    ProviderClients clients;
```

Add the endpoint + records (place after the `check` method / `CheckResult` record):
```java
    /**
     * Confirm a specific repository exists and is reachable with the provider's stored token — a
     * pre-flight for webhook registration. No webhook is created; only a category of the failure is
     * returned. Repo scope only; org reachability is covered by {@link #check}.
     */
    @POST
    @Path("/{id}/verify-repo")
    public RepoCheck verifyRepo(@PathParam("id") String id, VerifyRepoRequest req) {
        RepoRef repo = parseRepo(req);
        ScmProvider provider = registry.resolveById(uuid(id))
                .orElseThrow(() -> new NotFoundException("No provider " + id));
        try {
            clients.diffSource(provider).assertRepoAccessible(repo);
            return new RepoCheck(true, null);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Repo verify failed for %s (type %s) repo %s", id, provider.type(), repo.full());
            return new RepoCheck(false,
                    reasonWithNotFound(e, "Repository not found, or the token cannot see it (HTTP 404)."));
        }
    }

    /** The repository to verify: a full {@code owner/repo} path (repo scope). */
    public record VerifyRepoRequest(String repo) {
    }

    /** Result of {@link #verifyRepo}: {@code detail} carries the failure reason (null when ok). */
    public record RepoCheck(boolean ok, String detail) {
    }

    private static RepoRef parseRepo(VerifyRepoRequest req) {
        String repo = req == null || req.repo() == null ? "" : req.repo().trim();
        int slash = repo.indexOf('/');
        if (slash <= 0 || slash >= repo.length() - 1) {
            throw new BadRequestException("repo must be 'owner/repo'");
        }
        return new RepoRef(repo.substring(0, slash), repo.substring(slash + 1));
    }
```

Refactor the existing `reason(...)` so verify can supply repo-appropriate 404 wording (behaviour of the existing `/check` is preserved):
```java
    /** A non-leaky, actionable reason — status codes are safe; upstream bodies are not echoed. */
    private static String reason(RuntimeException e) {
        return reasonWithNotFound(e, "Not found (HTTP 404) — check the base URL.");
    }

    private static String reasonWithNotFound(RuntimeException e, String notFound) {
        if (e instanceof ScmApiException api) {
            int status = api.status();
            if (status == 401 || status == 403) {
                return "Authentication failed (HTTP " + status + ") — check the token and its scopes.";
            }
            if (status == 404) {
                return notFound;
            }
            if (status == 429) {
                return "Rate limited (HTTP 429) — try again shortly.";
            }
            return "Provider returned HTTP " + status + ".";
        }
        return "Could not reach the provider (network or TLS error).";
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-orchestrator:test --tests "*ProviderResourceTest*"`
Expected: PASS (4 new + existing).

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderResourceTest.java
git commit -m "Add provider verify-repo endpoint reusing the SCM read client"
```

---

## Task 3: UI webhook form — registered-provider picker + owner-from-workspace

**Files:**
- Modify: `spire-ui/src/components/SettingsWebhookRepos.tsx`
- Modify: `spire-ui/src/index.css`
- Test: `spire-ui/src/components/SettingsWebhookRepos.form.test.tsx` (create)

**Interfaces:**
- Consumes: `fetchProviders(): Promise<ProviderView[]>` (`ProviderView` has `id, name, type, workspace, enabled`), `webhookTargetHelp(providerType, scope)` (existing), `createWebhookRepo`/`updateWebhookRepo`.
- Produces: the reworked `WebhookRepoFormModal`. Task 4 inserts a Verify button into the repo-scope `.wh-repo-input` row and reads `selectedProvider` + `target`.

**Context:** The current modal (`SettingsWebhookRepos.tsx:192-345`) uses a provider-**type** `<select>` (`PROVIDER_TYPES`) + a free-text `target`. Replace with a **registered-provider** picker; the owner is fixed from the provider's `workspace`, so repo scope asks only for the repo slug (`target = owner/slug`) and org scope targets the workspace. Keep the reveal modal, secret rotate, payload-URL display, and the list table unchanged.

- [ ] **Step 1: Write the failing render test**

Create `SettingsWebhookRepos.form.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import * as api from '../api';

const provider = (over: Partial<api.ProviderView>): api.ProviderView => ({
  id: 'p1', name: 'Acme Bot', type: 'github', baseUrl: 'https://api.github.com', workspace: 'acme',
  authKind: 'bearer', authUsername: null, hasSecret: true, botAccountId: 'b1', enabled: true,
  authors: [], conversationLevel: null, createdAt: '2026-07-23T00:00:00Z', ...over,
});

describe('WebhookRepoFormModal — provider picker', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([]);
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      provider({ id: 'p1', name: 'Acme Bot', type: 'github', workspace: 'acme' }),
      provider({ id: 'p2', name: 'Lab Bot', type: 'gitlab', workspace: 'my-team' }),
    ]);
  });

  it('lists registered providers and fixes the owner for repo scope', async () => {
    render(<SettingsWebhookRepos />);
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    // both registered providers are offered
    await waitFor(() => expect(screen.getByRole('option', { name: /Acme Bot · github · acme/ })).toBeInTheDocument());
    expect(screen.getByRole('option', { name: /Lab Bot · gitlab · my-team/ })).toBeInTheDocument();
    // repo scope shows the fixed owner prefix from the first provider's workspace
    expect(screen.getByText('acme/')).toBeInTheDocument();
  });

  it('shows an empty state when no providers are registered', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    render(<SettingsWebhookRepos />);
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await waitFor(() => expect(screen.getByText(/register a provider first/i)).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run (from `spire-ui`): `npx vitest run src/components/SettingsWebhookRepos.form.test.tsx`
Expected: FAIL — the form still renders a type dropdown / no `acme/` prefix; no empty state.

- [ ] **Step 3: Update imports + drop the dead type list**

In `SettingsWebhookRepos.tsx`, extend the api import and remove `PROVIDER_TYPES` (now unused):
```tsx
import {
  createWebhookRepo,
  deleteWebhookRepo,
  fetchProviders,
  fetchWebhookRepos,
  rotateWebhookSecret,
  updateWebhookRepo,
  type ProviderView,
  type WebhookRepoInput,
  type WebhookRepoSecret,
  type WebhookRepoView,
  type WebhookScope,
} from '../api';
```
Delete the `const PROVIDER_TYPES = [...] as const;` line (keep `SCOPES`).

- [ ] **Step 4: Replace `WebhookRepoFormModal` with the provider-picker version**

Replace the whole `WebhookRepoFormModal` function body (`:192` through its closing `}`) with:
```tsx
function WebhookRepoFormModal({
  initial,
  onClose,
  onSaved,
}: {
  initial: WebhookRepoView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = initial !== null;

  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [providersLoaded, setProvidersLoaded] = useState(false);
  const [providerId, setProviderId] = useState('');
  const [scope, setScope] = useState<WebhookScope>(initial?.scope ?? 'repo');
  const [slug, setSlug] = useState(() => {
    if (initial && initial.scope === 'repo') {
      const i = initial.target.indexOf('/');
      return i >= 0 ? initial.target.slice(i + 1) : '';
    }
    return '';
  });
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<WebhookRepoSecret | null>(null);

  useEffect(() => {
    let alive = true;
    fetchProviders()
      .then((all) => {
        if (!alive) return;
        const usable = all.filter((p) => p.enabled);
        setProviders(usable);
        if (initial) {
          const owner = initial.scope === 'org' ? initial.target : initial.target.split('/')[0];
          const match = usable.find((p) => p.type === initial.providerType && p.workspace === owner);
          setProviderId(match?.id ?? '');
        } else if (usable.length > 0) {
          setProviderId(usable[0].id);
        }
      })
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)))
      .finally(() => alive && setProvidersLoaded(true));
    return () => {
      alive = false;
    };
  }, [initial]);

  const selectedProvider = providers.find((p) => p.id === providerId) ?? null;
  // On edit, if the provider was deleted we can't derive the owner — fall back to the stored row (read-only).
  const legacyEdit = editing && providersLoaded && !selectedProvider;
  const owner = selectedProvider?.workspace ?? (legacyEdit ? initial!.target.split('/')[0] : '');
  const providerType = selectedProvider?.type ?? initial?.providerType ?? '';
  const target = legacyEdit ? initial!.target : scope === 'org' ? owner : `${owner}/${slug.trim()}`;
  const targetHelp = webhookTargetHelp(providerType, scope);
  const valid = legacyEdit
    ? true
    : selectedProvider != null && (scope === 'org' ? owner.length > 0 : /^[^/\s]+$/.test(slug.trim()));
  const noProviders = providersLoaded && providers.length === 0 && !legacyEdit;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!valid) {
      setError(selectedProvider == null ? 'Select a provider first.' : 'Repository must be a single name (no slash).');
      return;
    }
    const input: WebhookRepoInput = { providerType, scope, target, enabled };
    setBusy(true);
    setError(null);
    try {
      if (editing && initial) {
        await updateWebhookRepo(initial.id, input);
        onSaved();
      } else {
        setRevealed(await createWebhookRepo(input));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function rotate() {
    if (!initial) return;
    setBusy(true);
    setError(null);
    try {
      setRevealed(await rotateWebhookSecret(initial.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  if (revealed) {
    return <SecretRevealModal result={revealed} rotated={editing} onDone={onSaved} />;
  }

  return (
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{editing ? 'Edit webhook' : 'Add webhook'}</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <form className="modal-body scroll" onSubmit={submit}>
          {noProviders ? (
            <div className="modal-msg">
              Register a provider first under Settings → Providers, then add a webhook for one of its repositories.
            </div>
          ) : (
            <>
              <div className="field-row-2">
                <label className="field">
                  <span>Provider</span>
                  {legacyEdit ? (
                    <div className="mono field-static">
                      {initial!.providerType} · {owner}
                    </div>
                  ) : (
                    <select value={providerId} onChange={(e) => setProviderId(e.target.value)}>
                      {providers.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name} · {p.type} · {p.workspace}
                        </option>
                      ))}
                    </select>
                  )}
                </label>
                <label className="field">
                  <span>Scope</span>
                  <select
                    value={scope}
                    onChange={(e) => setScope(e.target.value as WebhookScope)}
                    disabled={legacyEdit}
                  >
                    {SCOPES.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              {scope === 'repo' ? (
                <label className="field">
                  <span>Repository</span>
                  <div className="wh-repo-input">
                    <span className="wh-owner mono">{owner || '—'}/</span>
                    <input
                      className="mono"
                      placeholder="repo-name"
                      value={slug}
                      onChange={(e) => setSlug(e.target.value)}
                      disabled={legacyEdit}
                      autoFocus
                    />
                    {/* Task 4 inserts the Verify button here */}
                  </div>
                  <small className="field-hint">{targetHelp.hint}</small>
                </label>
              ) : (
                <label className="field">
                  <span>Organization</span>
                  <div className="mono field-static">{owner || '—'}</div>
                  <small className="field-hint">{targetHelp.hint}</small>
                </label>
              )}

              {editing && initial && (
                <div className="field">
                  <span>Webhook secret</span>
                  <div className="secret-row">
                    <div className="mono field-static">Stored — write-only</div>
                    <button type="button" className="btn-ghost" onClick={rotate} disabled={busy}>
                      <RotateCw size={14} />
                      Rotate
                    </button>
                  </div>
                  <small className="field-hint">
                    The secret is never shown after creation. Rotate to mint a new one — paste it into the provider’s
                    webhook settings (the old value stops working).
                  </small>
                </div>
              )}

              {editing && initial && (
                <label className="field">
                  <span>Payload URL (path)</span>
                  <div className="mono field-static">{webhookPath(initial)}</div>
                  <small className="field-hint">Prefix with your public webhook base to get the full payload URL.</small>
                </label>
              )}

              <label className="field-check">
                <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                <span>Enabled</span>
              </label>
            </>
          )}

          {error && <div className="modal-msg modal-error">{error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy || (!editing && noProviders)}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add webhook'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Add the repo-input layout CSS**

In `index.css`, after the `.wh-setup` block added earlier, append:
```css
  /* Fixed-owner repo input: read-only owner prefix + the repo-name field. */
  .wh-repo-input { display: flex; align-items: center; gap: 6px; }
  .wh-repo-input .wh-owner { color: var(--text-3); white-space: nowrap; font-size: 12.5px; }
  .wh-repo-input input { flex: 1; min-width: 0; }
```

- [ ] **Step 6: Run the form test + typecheck**

Run (from `spire-ui`): `npx vitest run src/components/SettingsWebhookRepos.form.test.tsx && npx tsc --noEmit`
Expected: PASS + no type errors.

- [ ] **Step 7: Commit**

```bash
git add spire-ui/src/components/SettingsWebhookRepos.tsx \
        spire-ui/src/components/SettingsWebhookRepos.form.test.tsx \
        spire-ui/src/index.css
git commit -m "Pick a registered provider in the webhook form, fixing the owner"
```

---

## Task 4: UI Verify-repository button + `api.verifyRepo`

**Files:**
- Modify: `spire-ui/src/api.ts`
- Modify: `spire-ui/src/components/SettingsWebhookRepos.tsx`
- Modify: `spire-ui/src/index.css`
- Test: `spire-ui/src/components/SettingsWebhookRepos.form.test.tsx`

**Interfaces:**
- Consumes: `POST /api/providers/{id}/verify-repo` (Task 2), `selectedProvider`/`target`/`scope`/`slug` from the Task 3 modal.
- Produces: `RepoCheck` type + `verifyRepo(providerId, repo)` in `api.ts`; a Verify button + inline indicator in the repo-scope row.

- [ ] **Step 1: Write the failing verify test**

Add to `SettingsWebhookRepos.form.test.tsx`:
```tsx
  it('verifies the repository via the selected provider', async () => {
    const spy = vi.spyOn(api, 'verifyRepo').mockResolvedValue({ ok: true, detail: null });
    render(<SettingsWebhookRepos />);
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await screen.findByText('acme/');
    fireEvent.change(screen.getByPlaceholderText('repo-name'), { target: { value: 'widgets' } });
    fireEvent.click(screen.getByRole('button', { name: /verify/i }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith('p1', 'acme/widgets'));
    expect(await screen.findByText(/repository found/i)).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/components/SettingsWebhookRepos.form.test.tsx`
Expected: FAIL — no Verify button / `api.verifyRepo` undefined.

- [ ] **Step 3: Add the api client**

In `api.ts`, after `checkProvider` (`:334`):
```ts
export interface RepoCheck {
  ok: boolean;
  detail: string | null;
}

// Live check that a repo exists and is reachable with the provider's token (no webhook is created).
export async function verifyRepo(providerId: string, repo: string): Promise<RepoCheck> {
  const res = await fetch(`/api/providers/${encodeURIComponent(providerId)}/verify-repo`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repo }),
  });
  if (!res.ok) await throwResponse(res, 'Failed to verify repository');
  return res.json();
}
```

- [ ] **Step 4: Add the Verify button + indicator to the modal**

In `SettingsWebhookRepos.tsx`, extend the api import with `verifyRepo` and `type RepoCheck`. Add verify state to `WebhookRepoFormModal` (after the `revealed` state):
```tsx
  const [verify, setVerify] = useState<{ state: 'idle' | 'checking' | 'ok' | 'fail'; detail?: string }>({
    state: 'idle',
  });

  // A changed provider / scope / slug invalidates a prior verify result.
  useEffect(() => setVerify({ state: 'idle' }), [providerId, scope, slug]);

  async function onVerify() {
    if (!selectedProvider) return;
    setVerify({ state: 'checking' });
    try {
      const result: RepoCheck = await verifyRepo(selectedProvider.id, target);
      setVerify(result.ok ? { state: 'ok' } : { state: 'fail', detail: result.detail ?? 'Not reachable' });
    } catch (err) {
      setVerify({ state: 'fail', detail: err instanceof Error ? err.message : String(err) });
    }
  }
```

Replace the `{/* Task 4 inserts the Verify button here */}` comment inside `.wh-repo-input` with:
```tsx
                    <button
                      type="button"
                      className="btn-ghost wh-verify-btn"
                      onClick={() => void onVerify()}
                      disabled={legacyEdit || !selectedProvider || !/^[^/\s]+$/.test(slug.trim()) || verify.state === 'checking'}
                    >
                      {verify.state === 'checking' ? 'Verifying…' : 'Verify'}
                    </button>
```

Add the indicator line right below the `.wh-repo-input` div (still inside the repo `<label>`, before the `.field-hint`):
```tsx
                  {verify.state === 'ok' && <div className="wh-verify ok">Repository found</div>}
                  {verify.state === 'fail' && <div className="wh-verify fail">{verify.detail}</div>}
```

- [ ] **Step 5: Add the indicator CSS**

In `index.css`, after `.wh-repo-input`:
```css
  .wh-repo-input .wh-verify-btn { flex: none; white-space: nowrap; }
  .wh-verify { margin-top: 6px; font-size: 12px; }
  .wh-verify.ok { color: var(--good); }
  .wh-verify.fail { color: var(--crit); }
```

- [ ] **Step 6: Run the form test + full UI suite + typecheck**

Run (from `spire-ui`): `npx vitest run src/components/SettingsWebhookRepos.form.test.tsx && npx tsc --noEmit && npx vitest run`
Expected: PASS (verify test + full suite) and no type errors.

- [ ] **Step 7: Commit**

```bash
git add spire-ui/src/api.ts spire-ui/src/components/SettingsWebhookRepos.tsx \
        spire-ui/src/components/SettingsWebhookRepos.form.test.tsx spire-ui/src/index.css
git commit -m "Add Verify repository button to the webhook form"
```

---

## Final verification (after all tasks)

- [ ] `./gradlew :spire-contract:test :spire-scm-github:test :spire-scm-gitlab:test :spire-scm-bitbucket:test :spire-orchestrator:test` — all green (module-scoped; the full `build`'s `quarkusAppPartsBuild` is a KNOWN pre-existing toolchain failure).
- [ ] From `spire-ui`: `npx tsc --noEmit && npx vitest run` — clean, all pass.
- [ ] Confirm `spire-gateway` is untouched and the webhook-create payload is still `{ providerType, scope, target, enabled }`.

## Success criteria (from the spec)

1. Add-webhook offers registered providers (name · type · workspace), not a bare type list.
2. Choosing a provider fixes the owner; repo scope asks only for the repo name; org scope targets the workspace.
3. A Verify button confirms the repo exists/reachable via the provider's token and reports exists / not-found / unauthorized inline, without creating the webhook.
4. The gateway create payload and its isolation are unchanged; the token never leaves the orchestrator.
5. New adapter + REST + UI tests green; existing suites unaffected.
