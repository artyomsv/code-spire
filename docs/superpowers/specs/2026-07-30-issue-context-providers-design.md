# Issue-reference context providers (GitHub + GitLab) — design

**Status:** approved 2026-07-30
**Roadmap item:** E14
**Depends on:** the `ContextProvider` / `ContextReferenceSource` SPI proven by Jira (2026-07-08) and
Confluence (2026-07-09); ADR-015 (brokered credentials); ADR-020 (provider neutrality).

---

## Problem

A review can already pull the Jira ticket or Confluence page a PR references. Teams that track work
in GitHub Issues or GitLab Issues get nothing — the reviewer sees the diff but not the requirement it
is meant to satisfy, so it cannot tell "this is wrong" from "this is deliberately different from what
the ticket asked for".

## Goal

A PR that references an issue in its title, branch name or description gets that issue's content into
the review prompt, on GitHub and GitLab, with the same encrypted-registry, connectivity-check and
preview treatment Jira and Confluence already have.

## Non-goals

- **No cross-registry credential reuse.** Rejected during design in favour of the simpler
  Jira/Confluence shape (see *Credential model*).
- **No per-repo context configuration.** The registry stays global-with-optional-narrowing, as today.
- **No issue *writing*.** Read-only context, like every other provider.
- **No new SCM capability.** This is a context source that happens to talk to an SCM's API; it does
  not touch `ScmIngress`, `DiffSource`, `CommentSink` or the review loop.

---

## Architecture

Two new adapter modules, structurally identical to `spire-context-jira` — framework-free (JDK
`HttpClient` + Jackson), SSRF-guarded, Apache-2.0 per ADR-021:

```
spire-context-github/
  GitHubIssueApiException     status + neutral message; never carries a response body
  GitHubIssueClient           JDK HttpClient, bearer auth, host-pinned manual redirects
  GitHubIssueConfig           baseUrl, authKind, secret, repoAllowList
  GitHubIssueContextProvider  ContextProvider — narrows, fetches, shapes
  GitHubIssueReferenceSource  ContextReferenceSource — credential-free extraction
  GitHubIssueRefs             the grammar: match, parse, normalize

spire-context-gitlab/         same six, plus epic + merge-request resolution
```

`source()` constants: `GITHUB_ISSUES`, `GITLAB_ISSUES`. Registry type strings: `github-issues`,
`gitlab-issues` — deliberately **not** `github`/`gitlab`, which are SCM types in an
identically-shaped registry, and confusing the two would be an easy operator error.

### Why two modules rather than one

The two APIs differ in path shape (`/repos/{owner}/{repo}/…` vs URL-encoded
`/projects/{group%2Fsub%2Fproj}/…`), JSON field names (`body` vs `description`), comment endpoints,
and reference grammar (GitLab adds `!` and `&`). One module would be a class with two branches on
provider — precisely what ADR-020 exists to prevent, merely relocated outside core. It also mirrors
the established `spire-scm-*` and `spire-context-*` layout.

---

## The repo-relative hazard, and the guard

`PROJ-123` is globally unique within a Jira site. **`#123` is meaningless without a repository and a
host.** Two facts make this dangerous rather than merely incomplete:

1. `GatherContext` carries `RepoRef{workspace, slug}` but **not** which SCM the review runs on.
2. The same `workspace/slug` routinely exists on more than one host. In this project's own test
   setup, `artyomsv/spire-test` exists on both GitHub and Bitbucket.

So a GitHub-Issues provider resolving `#123` during a review of the *Bitbucket* PR would fetch a
different project's issue and feed it to the model as authoritative context for unrelated code. No
error, no empty result — a plausible wrong answer. This is the same defect class as the
cross-provider resolution bug fixed on 2026-07-25, whose fix was to disambiguate by the review's
stored `provider_type`.

**Guard 1 — `ScmType` on the request (mandatory).** Add `ScmType scmType` to `GatherContext` and
`ContextRequest`.

The gate is **per reference, not per provider**. A *bare* `#123` borrows the review's own repository,
so it resolves only when `scmType` matches the provider's own axis. A *qualified* `owner/repo#123` or
a full URL names its repository outright and needs no gate — an author who writes `acme/widgets#12` on
a Bitbucket PR meant that GitHub issue, and refusing it would discard context they deliberately
supplied.

`ResultSaga` builds `GatherContext` on `DiffFetched` (line ~122) and reads the type from the existing
`ReviewProviderResolver.resolveForReview(reviewId)` — the same shared path introduced by the 2026-07-25
cross-provider fix, so there is one place that answers "which SCM is this review on". `ContextWorker`
copies the value onto each `ContextRequest` it builds.

**Fails closed.** An absent provider row yields a null `scmType`, and a repo-relative provider that
cannot confirm the axis contributes nothing rather than guessing. By `DiffFetched` a provider must
exist (the diff fetch needed its credential), so null means something is wrong, and the safe response
to "I don't know which host this is" is silence.

This stays provider-neutral: core *carries* the value and never branches on it, and `ScmType` is
already the allowlisted enum that declares the names.

**No upcaster needed.** `ContextRequest` appears only inside `ContextRequested`, a bus-only
integration event — it is not in the persisted `DomainEvent` hierarchy, and work queues read from
`latest` offsets, so no stored payload and no replayed message predates the field. (Verified, not
assumed: `ContextRequested` has no consumer in `spire-orchestrator/src/main`.)

**Guard 2 — optional owner/repo allow-list.** The generic `projectKeys` registry column is reused as
a comma-separated owner or `owner/repo` allow-list, exactly as Confluence reuses it for space keys —
so **no migration**. This disambiguates the remaining case `ScmType` cannot: two GitHub instances
(github.com and a GHE host) both claiming `#123`. Blank means accept any repo on the configured host.

---

## Reference grammar

Both extractors emit the **raw matched token**; the provider parses it. Extraction is stateless,
credential-free and repo-free, because it runs at diff-fetch time before any credential is brokered.

| Form | Example | Emitted | Resolved against |
|---|---|---|---|
| bare issue | `fixes #123` | `#123` | `request.repo()` |
| qualified | `org/repo#123`, `group/sub/proj#123` | as written | the named repo |
| issue URL | `https://github.com/org/repo/issues/123` | the URL | the URL's repo |
| PR/MR URL | `…/pull/123`, `…/-/merge_requests/123` | the URL | the URL's repo |
| GitLab MR | `!45` | `!45` | `request.repo()` |
| GitLab epic | `&7` | `&7` | the project's ancestor group |

Patterns (both extractors, `(?<![\w/-])` prefix so `abc#1` and a path fragment do not match):

- bare: `(?<![\w/-])#(\d{1,7})\b`
- qualified, **GitHub** — exactly one slash, since `owner/repo` is the whole namespace:
  `\b([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#(\d{1,7})\b`
- qualified, **GitLab** — one or more, for nested groups:
  `\b((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+)#(\d{1,7})\b`
- GitLab only: `(?<![\w/-])!(\d{1,7})\b`, `(?<![\w/-])&(\d{1,7})\b`
- URLs: matched on the path segment (`/issues/`, `/pull/`, `/-/issues/`, `/-/merge_requests/`,
  `/-/epics/`) so a host-agnostic pattern works for GHE and self-managed GitLab

`normalize()` lowercases the repo portion and strips a trailing `/`, so `Org/Repo#12` and
`org/repo#12` dedupe across rounds.

**Known, accepted noise:** a CSS hex colour `#123456` matches the bare form and produces one 404,
which the skip path absorbs. Guarding against it would need to distinguish colours from issue numbers
by content, which is not reliably possible. The reference cap below bounds the cost.

**Cross-form duplicates resolve themselves.** `#123` and `org/repo#123` for the same issue cannot
normalize to one form — the extractor has no repo — so both may be fetched. `ContextWorker` already
de-duplicates assembled items by `uri()`, so one item reaches the prompt. No new dedup logic.

### Pull requests and merge requests as context

Bare `#123` on GitHub shares an id space with pull requests: the issues endpoint returns a PR when
the number is a PR's. Rather than filter these out, resolve them and **label the kind honestly**.
"Supersedes #120" is real context, and silently dropping it is a loss the operator cannot see. New
`ContextItem` kinds: `ISSUE`, `PULL_REQUEST`, `EPIC` — neutral names (`PULL_REQUEST` is the house
term, cf. the `PullRequest` contract record), so core's kind list gains no provider vocabulary.

---

## Credential model

**Own token in the context registry, exactly like Jira and Confluence.** Decided over pointing at a
registered SCM provider's credential.

| | Chosen: own token | Rejected: reuse SCM credential |
|---|---|---|
| Plumbing | None — existing registry, existing packing path | New cross-registry read while packing `ContextCredential` |
| Registries | Stay independent | Context packing depends on SCM registry state |
| Secrets | Duplicated for one host | Single source of truth |
| Rotation | Two places; missing one degrades context to an `ERROR` contribution | One place |

The rotation cost is accepted: an `ERROR` contribution is visible on the review timeline, and the
registry's own `last_check_ok` (V28) surfaces a rejected context credential in the attention panel.

Fields, all existing columns:

| Column | GitHub | GitLab |
|---|---|---|
| `baseUrl` | `https://api.github.com`, or `https://ghe.host/api/v3` | `https://gitlab.com`, or self-managed root |
| `authKind` | `bearer` only | `bearer` only |
| `secret` | PAT (classic or fine-grained) | PAT |
| `projectKeys` | owner / `owner/repo` allow-list, blank = any | same |

`basic` is rejected on save with a clear message: GitHub's basic auth is deprecated and GitLab's PATs
work on the OAuth-compliant `Authorization: Bearer` header (the same choice `GitLabConfig` already
documents for the SCM adapter). `username` is unused for both.

---

## API calls

Bounded at **10 references per provider per level** (`MAX_REFERENCES`) — a cost guard the
single-ticket Jira case never needed, but a description with fifty issue links would otherwise turn
into a hundred API calls inside a 20-second budget.

**GitHub** (`{baseUrl}` already includes `/api/v3` on GHE):

| Purpose | Call |
|---|---|
| issue or PR | `GET /repos/{owner}/{repo}/issues/{n}` — `title`, `body`, `state`, `labels[].name`, `html_url`; the presence of a `pull_request` key means it is a PR |
| comments | `GET /repos/{owner}/{repo}/issues/{n}/comments?per_page=100` |
| connectivity check | `GET /user` |

**GitLab** (project paths URL-encoded, per `GitLabProjectPath` / `GitLabDiffSource` precedent):

| Purpose | Call |
|---|---|
| issue | `GET /api/v4/projects/{path}/issues/{iid}` — `title`, `description`, `state`, `labels[]`, `web_url` |
| notes | `GET /api/v4/projects/{path}/issues/{iid}/notes?per_page=100` — skip `system: true` notes, which are activity noise ("changed title from…") |
| merge request | `GET /api/v4/projects/{path}/merge_requests/{iid}` |
| epic | `GET /api/v4/groups/{group}/epics/{iid}` |
| connectivity check | `GET /api/v4/user` |

**Epic group derivation.** Epics live at group level, and a project path does not say which ancestor
group owns them. Try the immediate parent namespace (`group/sub` for `group/sub/proj`), then on 404
the top-level group (`group`). Two calls worst case, and no further guessing.

Comments and notes are included for the same reason Jira's are: the decision that explains the code
often lives in the discussion, not the description. Bounded to the last 5, 500 characters each,
matching `JiraContextProvider`'s constants; descriptions clipped at 4,000 characters.

---

## Error handling

Following `JiraContextProvider` exactly, with one addition:

| Condition | Behaviour |
|---|---|
| 404 on a reference | Skip that reference, keep the rest — a typo must not lose the other tickets |
| 401 / 403 | `ERROR` contribution for the whole provider; the review continues without this source |
| Epic 403/404 on a non-Premium instance | **Skip that reference only.** Epics are a GitLab Premium feature; a free-tier operator must not lose their issue context to an epic they were never able to read |
| Provider exceeds the 20s fan-out budget | Existing `ContextWorker` behaviour — `ERROR` contribution, review proceeds |

No response body is ever persisted or logged on an auth failure: a 401 body can echo the token.
`*ApiException` carries the status and a fixed message.

---

## Registry and UI surface

The five dispatch sites, four of which are already ADR-020-allowlisted composition roots, so **no new
allowlist entries**:

| Site | Change |
|---|---|
| `WorkerContextClients` | two `case` arms building the providers from the credential |
| `WorkerContextReferences` | two extractors added to the list |
| `ContextProviderResource` | `TYPES` gains both; `preview` gains two branches |
| `ContextKeyValidator` | check paths `/user` and `/api/v4/user` |
| `SettingsContextProviders.tsx` | two types in the selector, with type-aware copy |

**Preview has no PR**, so a bare `#123` cannot be resolved there. Preview accepts a qualified
reference or a URL and, given a bare one, returns the actionable message *"`#123` needs a repository —
try `owner/repo#123` or paste the issue URL"* rather than a confusing empty result.

UI copy per type: the `projectKeys` field is labelled "Owner/repo allow-list (optional)" with a
placeholder showing `acme` and `acme/widgets`; the baseUrl hint names the cloud default and the
self-hosted shape. lucide-react icons only.

---

## Testing

**Grammar units** per form, including the negatives that matter: `abc#1` (not a reference),
`#123456` (matches, documented as accepted noise), `!` and `&` inside prose, nested-group qualified
refs, and both URL shapes on a self-hosted host.

**WireMock per adapter** for: issue fetch and shaping, PR-vs-issue discrimination via the
`pull_request` key, comment/note inclusion with `system` notes skipped, 404 skip preserving siblings,
401 producing an `ERROR` contribution, and the epic parent-then-top-level group fallback.

**The cross-wire regression test** — the reason `ScmType` exists on the request. A GitHub-Issues
provider given a **bare** `#123` and a request whose `scmType` is Bitbucket must report
`supports() == false` and issue **zero** HTTP calls. Asserted on the WireMock server having received
no requests, not merely on an empty contribution, so a provider that fetched and then discarded would
still fail. Its companion asserts the other half of the rule: the same provider given a *qualified*
reference on a Bitbucket review **does** resolve it.

**`spire-arch` stays green** with no allowlist change. Its scan asserts it reached every core module,
so new modules outside core cannot silently weaken it.

**Registry tests** mirroring the existing context-provider REST suite: save rejects `basic`, secret
never returned (`hasSecret` only), check records `last_check_ok`, preview rejects a bare reference
with the actionable message.

---

## Verification (the live pass)

Code-complete plus WireMock is not the bar; the bar is context reaching a real model call. For each
configured provider type — Jira, Confluence, GitHub Issues, GitLab Issues:

1. A real PR/MR whose title or description references a real issue in that system.
2. Confirm `ContextRequested` → `ContextContributed` (status `OK`, item count > 0) →
   `ContextAssembled` on the review timeline, with the source named.
3. **Confirm the text reached the prompt**, not merely the blob. The `{context}` slot is rendered by
   `ReviewPromptBuilder`; assert on the persisted prompt/model input for the run, so "assembled" and
   "sent" are distinguished. A blob that assembles and a prompt that omits it would look identical
   from the dashboard alone.
4. Confirm the review's output shows awareness of the context — the honest weak signal, recorded as
   observation rather than proof.
5. Negative pass: the `ScmType` guard — a PR on one SCM referencing `#123` while a *different* SCM's
   issue provider is configured and enabled must contribute nothing.

No fabricated issues or fixtures: real issues in real repositories, per the no-synthetic-data rule.

---

## Global constraints

- JDK 25, Quarkus 3.36, Gradle Kotlin DSL. Four-space Java indent, explicit types over `var`.
- Both modules are **Apache-2.0** (ADR-021): each carries its own `LICENSE`, `LICENSING.md` is
  updated, and neither may depend on a service module.
- `settings.gradle.kts` includes both.
- Pure domain code stays framework-free; the adapters use JDK `HttpClient` + Jackson only.
- SSRF guard on `baseUrl` (https + public host), as every other adapter has.
- No new user-visible occurrences of the working name.
- DTO naming: `*Dto` / `*View` / `*Payload` only.
- Money in millicents; host dev ports in 34xxx. (Neither applies here; listed so the plan inherits
  the full set.)
