# Webhook Form: Registered-Provider Picker + Verify Repository — Design

**Date:** 2026-07-23
**Status:** Approved (brainstorm), pending implementation plan

## Goal

Make the manual "Add webhook" flow (Settings → Webhooks) start from a **registered SCM provider**
instead of a bare provider-type dropdown, and let the operator **verify the target repository exists**
(reachable with that provider's token) before creating the webhook. Manual webhook creation stays;
no auto-provisioning.

## Why

Today the webhook form asks for a provider *type* (`github`/`gitlab`/`bitbucket-cloud`) and a free-text
`owner/repo`, because the webhook registry is owned by the **gateway**, which is deliberately isolated
from the provider/token registry (that lives in the **orchestrator**; a compromised edge must not read
tokens). But the operator has already registered the provider (host + token) under Settings → Providers,
so the form can (a) pick from those real providers and (b) confirm the repo exists — both **without
breaking isolation**, because the UI talks to both services and the verify call runs entirely on the
orchestrator (which holds the token). The gateway still stores only `{providerType, scope, target}`.

## Non-goals

- **No auto-provisioning** of webhooks via the SCM API (deliberate — least-privilege token, unknown
  public URL, and the gateway/orchestrator secret-vs-token split). Manual paste stays.
- **No org-scope repo verification** — "does this specific repo exist" is a repo-scope concern; org
  scope reachability is already covered by the existing provider `POST /api/providers/{id}/check`.
- **No broader token scope** — a repo GET works with the existing read token; verify needs nothing more.
- No change to the gateway's `webhook_repo` schema, its create/rotate/delete API, or the reveal modal.

## Architecture

The UI proxies `/api/webhook-repos` → gateway and everything else `/api/*` → orchestrator
(`vite.config.ts`). The picker is sourced from the orchestrator (`fetchProviders()`); the verify is a
new orchestrator endpoint. Only the **UI** bridges the two services; neither backend gains a new
cross-service dependency. The webhook create payload is unchanged, so the gateway remains type+target
based and isolated.

---

## Backend — repo-existence verify

### SPI: `DiffSource.assertRepoAccessible(RepoRef)`

Add a method to the existing `DiffSource` port (`spire-contract/.../port/DiffSource.java`):

```java
/**
 * Confirm the repository exists and is reachable with the configured token. Implementations GET the
 * repo resource; a non-2xx surfaces as {@link ScmApiException} (404 = missing or not visible to the
 * token, 401/403 = no access), which the caller classifies. Default is unsupported so stubs and other
 * DiffSource impls are unaffected — only the real SCM adapters implement it.
 */
default void assertRepoAccessible(RepoRef repo) {
    throw new UnsupportedOperationException(type() + " cannot verify a repository");
}
```

A `default` (not abstract) keeps `StubScm.StubDiffSource` and any other implementer compiling unchanged
— mirrors how `CommentSink.resolveThread`/`updateComment` are defaults.

Overrides (each uses the client's existing `getJson`; the returned JSON is ignored — success is
"no throw"):
- **GitHub** (`GitHubDiffSource`): `client.getJson("/repos/" + repo.full());`
- **GitLab** (`GitLabDiffSource`): `client.getJson("/projects/" + URLEncoder.encode(repo.full(), UTF_8));`
  (reuse the adapter's existing project-path encoding).
- **Bitbucket** (`BitbucketCloudDiffSource`): `client.getJson("/repositories/" + repo.full());`

### Endpoint: `POST /api/providers/{id}/verify-repo`

Add to `ProviderResource` (`spire-orchestrator/.../provider/ProviderResource.java`), mirroring the
existing `POST /{id}/check` (`:110-123`) and its `reason(RuntimeException)` classifier (`:130-145`):

- **Request** (`@Consumes(APPLICATION_JSON)`): `record VerifyRepoRequest(String repo)` — the full
  `owner/repo` path (repo scope). Reject blank / no-slash with `400` (`BadRequestException`).
- **Body → RepoRef:** split `repo` on the first `/` → `new RepoRef(owner, slug)`; `slug` is the
  remainder (single-segment at repo scope, which the gateway's create validation already enforces).
- **Handler:** `ScmProvider p = registry.resolveById(uuid(id))` → `DiffSource ds = clients.diffSource(p)`
  → `ds.assertRepoAccessible(repoRef)`. Success → `new RepoCheck(true, null)`. Catch `RuntimeException e`
  → `new RepoCheck(false, reason(e))`. A missing provider id yields the same `404` `resolveById` /
  `/check` already produces.
- **Response:** `record RepoCheck(boolean ok, String detail)` — `detail` is the classified failure
  reason (null when ok). `reason()` already maps `ScmApiException.status()`: 404 → not-found (worded
  "not found, or the token cannot see it" — GitHub returns 404 for private repos a token can't read),
  401/403 → unauthorized, 429 → rate-limited, network/other → generic.

`clients.diffSource(p)` is `ProviderClients.diffSource(ScmProvider)`; it already switches on type and
builds the token-bearing adapter. The provider's `baseUrl` was SSRF-validated at registration.

---

## UI — provider picker + owner-from-provider + Verify

All changes are in `SettingsWebhookRepos.tsx` (form modal) + `api.ts` (+ small CSS). The list table,
gateway payload, reveal modal (with the just-built Option-B checklist), rotate/delete, and
`webhookPath` are unchanged.

### `api.ts`

```ts
export interface RepoCheck { ok: boolean; detail: string | null; }

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

### Form modal (`WebhookRepoFormModal`)

- Load registered providers via `fetchProviders()` (only `enabled` ones offered).
- **Provider picker** replaces the provider-type `<select>`: options labeled `${p.name} · ${p.type} · ${p.workspace}`, value = `p.id`. Selecting it fixes the **type** (`p.type`) and the **owner** (`p.workspace`).
- **Owner is fixed from the provider's workspace:**
  - **Repository scope** → a read-only `${workspace}/` prefix + a **Repository** input for the slug;
    `target = ${workspace}/${slug.trim()}`. Validation: slug is a single non-empty, slash-free segment.
  - **Organization scope** → `target = ${workspace}` (shown read-only; no free text).
- **Verify** button (repo scope only), disabled until a provider + non-empty slug: calls
  `verifyRepo(p.id, target)` and renders an inline indicator — `checking` / `ok` ("Repository found") /
  `fail` (the returned `detail`) — mirroring `SettingsProviders`' `ConnState`/`ConnCell` pattern. Verify
  is advisory; it never blocks **Add webhook**.
- **Create payload unchanged:** `createWebhookRepo({ providerType: p.type, scope, target, enabled })`.
- **Empty state:** when no enabled providers exist, the form shows "Register a provider first
  (Settings → Providers)" and disables Add.
- **Edit mode:** preselect the provider whose `type` + `workspace` match the row's `providerType` and
  the owner parsed from `target`; prefill the slug from the remainder. If no provider matches (it was
  deleted), fall back to a disabled option showing the stored `providerType`/owner so edit still works
  on `enabled`/slug without forcing a re-pick.

### CSS

Reuse the provider connectivity indicator styling; add only what the inline verify row needs (a
`.wh-verify` row with the state colors already defined — `--good`, `--crit`, `--text-3`).

---

## Data flow

1. Form loads registered providers (orchestrator) + webhook rows (gateway).
2. Operator picks a provider → type + owner fixed → scope → repo slug (repo scope).
3. (Optional) **Verify** → `POST /api/providers/{id}/verify-repo {repo}` → orchestrator resolves the
   provider, GETs the repo with its token, returns `{ok, detail}` → inline indicator.
4. **Add webhook** → `POST /api/webhook-repos {providerType, scope, target, enabled}` (gateway) → mints
   key + secret → the existing reveal modal (Option-B per-provider checklist).

The token is used only in step 3, only by the orchestrator; the gateway never sees it.

## Error handling

- Verify never throws for HTTP/network failures — it returns `{ok:false, detail}`. Only a truly malformed
  request (blank repo) is a `400`. A missing provider id is a `404` (same as `/check`).
- 404 detail is worded so the operator knows it can mean "private repo the token can't see," not only
  "doesn't exist."
- Verify is advisory: a failed or un-run verify does not block creating the webhook (the operator may
  know better; the repo may be created later).

## Testing

- **Per-adapter WireMock** (`GitHubApiTest` / `GitLabApiTest` / `BitbucketCloudApiTest`):
  `assertRepoAccessible` hits the correct repo path; 200 → no throw; 404 → `ScmApiException.isNotFound()`;
  401 → `ScmApiException` with `status()==401`.
- **Orchestrator REST-layer** test for `verify-repo`: ok for a stubbed 200; `{ok:false, detail}` for
  404 and 401; `400` for a blank/no-slash repo; `404` for an unknown provider id — mirroring the
  existing `ProviderResource` `/check` test harness.
- **UI vitest** (`SettingsWebhookRepos`): the picker lists registered providers; choosing one + repo
  scope shows the `workspace/` prefix + slug field; the Verify button calls `verifyRepo` and renders
  ok/fail; the empty state appears with no providers. `tsc --noEmit` clean.

## Success criteria

1. The Add-webhook form offers registered providers (name · type · workspace), not a bare type list.
2. Choosing a provider fixes the owner; repo scope asks only for the repo slug; org scope targets the
   workspace.
3. A **Verify** button confirms the repo exists/reachable via the provider's token and reports
   exists / not-found / unauthorized inline, without creating the webhook.
4. The gateway create payload and its isolation are unchanged; the token never leaves the orchestrator.
5. New adapter + REST + UI tests green; existing suites unaffected.

## Risks / notes

- **GitHub 404 ambiguity:** a private repo invisible to the token returns 404, indistinguishable from
  "doesn't exist." The detail wording covers this; not a defect.
- **Edit-mode provider match** relies on (type, workspace) uniquely identifying the provider; that is
  the registry's own key (`resolve(type, workspace)`), so the match is exact. Deleted-provider fallback
  keeps edit functional.
- **Org-scope verify** is intentionally absent; if desired later it maps to an owner/workspace GET, a
  separate small addition.
