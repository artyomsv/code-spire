# SCM Provider Mapping

> Verified against the official APIs of Bitbucket Cloud (primary), GitHub, GitLab, and Bitbucket
> Data Center. This is the reference every `DiffSource`/`CommentSink`/`ScmIngress` adapter maps to.
> It proves the provider-neutral canonical model (DATA-MODEL.md §2) is a true common denominator — and
> records the quirks that forced specific fields. Status: **verified 2026-07**.

## The one design driver

Inline-comment anchoring and threading diverge hard across providers. The neutral model absorbs this by:
1. carrying **both `oldLine` and `newLine`** on every diff line,
2. carrying **`DiffRefs{baseSha, startSha, headSha}`** on the PR (GitLab needs all three; others need ≤1),
3. treating a thread as an **opaque `ThreadRef`** (a comment id for most, a *discussion id* for GitLab),
4. keying identity on a **stable `providerUserId`**, with `email` optional and never logged/persisted.

## 1. Identity → `Author{providerUserId, username, displayName, email?}`

| neutral | Bitbucket Cloud | GitHub | GitLab | Bitbucket DC |
|---|---|---|---|---|
| `providerUserId` (stable key) | `account_id` (or `uuid`) | `user.id` (int) | `user.id` / `author_id` | `user.id` |
| `username` (mutable handle) | `nickname` | `login` | `username` | `slug` / `name` |
| `displayName` | `display_name` | `name` (via API) | `name` | `displayName` |
| `email` | — none | — none by default | `public_email` (often null / `[REDACTED]`) | **`emailAddress` (exposed!)** |

Key on `providerUserId`; **never** key on username (mutable) or email. `email` is DC-only in practice,
optional, redaction-eligible, and must never be logged or persisted (SECURITY.md).

## 2. PR identity & refs → `PullRequest` + `DiffRefs`

| neutral | Bitbucket Cloud | GitHub | GitLab | Bitbucket DC |
|---|---|---|---|---|
| `prId` (repo-scoped int) | `pullrequest.id` | `number` | `iid` (not global `id`) | `id` |
| `headSha` | `source.commit.hash` **(12-char — expand via REST)** | `head.sha` | `last_commit.id` / `diff_refs.head_sha` | `fromRef.latestCommit` |
| `baseSha` | — (not needed) | `base.sha` | `diff_refs.base_sha` (**required to comment**) | — |
| `startSha` | — | — | `diff_refs.start_sha` (**required to comment**) | — |
| `sourceBranch`/`targetBranch` | `source/destination.branch.name` | `head/base.ref` | `source/target_branch` | `fromRef/toRef.displayId` |
| `description` (raw markdown) | `description` (HTML under `summary`, unreliable in hook) | `body` | `description` | `description` |

`DiffRefs` is fetched alongside the diff (GitLab: `/versions` or `diff_refs`; others populate what they have).

## 3. Diff → `Diff{DiffRefs, List<FilePatch>}`

| | Bitbucket Cloud | GitHub | GitLab | Bitbucket DC |
|---|---|---|---|---|
| format | **raw unified text** (302 redirect) + `diffstat` JSON | unified text (diff media type) **or** `/files` `patch` per file | unified text per file (`/diffs`) + `/versions` for SHAs | **structured JSON** (per-line `source`/`destination`) |
| change types | `modified/added/removed/renamed` (rename may **split** into add+remove) | `added/removed/modified/renamed/copied/changed` | `new_file/deleted_file/renamed_file` | segment types `ADDED/REMOVED/CONTEXT` |

Neutral: parse everything into `FilePatch{oldPath, newPath, change, hunks}` where each `Hunk` holds
`DiffLine{type, oldLine, newLine, content}` — **carrying both line numbers is what makes inline anchoring
work on every provider.** Unified-text providers are parsed with our own unified-diff hunk logic; DC's
structured diff maps directly.

## 4. Inline comment → `InlineAnchor{path, srcPath, oldLine?, newLine?, side}` (+ `DiffRefs`)

Derive from the `DiffLine`: **ADDED** → `side=NEW`, `newLine` only · **REMOVED** → `side=OLD`, `oldLine`
only · **CONTEXT** → both set, `side=NEW` by default.

| provider | how the adapter posts it |
|---|---|
| **Bitbucket Cloud** | `inline:{ path, to:newLine }` (NEW) or `inline:{ path, from:oldLine }` (OLD). Mutually exclusive — `from` wins if both sent. Multi-line: `start_to`/`start_from`. |
| **GitHub** | `{ commit_id: headSha, path, line: (OLD?oldLine:newLine), side: (OLD?LEFT:RIGHT) }`; multi-line adds `start_line`+`start_side`. (`position` = diff offset is **deprecated**.) |
| **GitLab** | discussion `position:{ position_type:text, base_sha, start_sha, head_sha, old_path:srcPath, new_path:path, old_line:(REMOVED/CONTEXT?oldLine:null), new_line:(ADDED/CONTEXT?newLine:null) }`. Wrong line/side combo → HTTP 400. |
| **Bitbucket DC** | `anchor:{ diffType:EFFECTIVE, path, srcPath, line:(OLD?oldLine:newLine), lineType:ADDED\|REMOVED\|CONTEXT, fileType:(OLD?FROM:TO) }`. |

## 5. Summary (PR-level) comment

| Bitbucket Cloud | GitHub | GitLab | Bitbucket DC |
|---|---|---|---|
| `POST …/pullrequests/{id}/comments` `{content:{raw}}` | `POST …/issues/{number}/comments` `{body}` | `POST …/merge_requests/{iid}/notes` `{body}` | `POST …/pull-requests/{id}/comments` `{text}` |

## 6. Reply in thread → `ThreadRef` (opaque)

| provider | `ThreadRef` value | reply call |
|---|---|---|
| Bitbucket Cloud | root **comment id** | `POST comments {content:{raw}, parent:{id}}` — inherits anchor |
| GitHub | root review **comment id** | `POST …/pulls/{n}/comments/{id}/replies {body}` or `{in_reply_to}` |
| GitLab | **discussion_id** (string, *not* a comment id) | `POST …/discussions/{discussion_id}/notes {body}` |
| Bitbucket DC | parent **comment id** | `POST comments {text, parent:{id}}` |

Replies inherit the parent's anchor on every provider — never resend the anchor.

## 7. Webhook events & signature (for `ScmIngress`)

| neutral action | Bitbucket Cloud | GitHub | GitLab | Bitbucket DC |
|---|---|---|---|---|
| PR opened | `pullrequest:created` | `pull_request` / `opened` | Merge Request / `open` | `pr:opened` |
| PR updated (new commits) | `pullrequest:updated` | `pull_request` / `synchronize` | Merge Request / `update` (`last_commit` changed) | `pr:from_ref_updated` |
| author replied | `pullrequest:comment_created` | `pull_request_review_comment` / `issue_comment` | Note Hook (on MR) | comment webhook |
| **PR closed (merged / declined)** | `pullrequest:fulfilled` / `pullrequest:rejected` | `pull_request` / `closed` (`merged` bool distinguishes) | Merge Request / `merge` or `close` | `pr:merged` / `pr:declined` / `pr:deleted` |
| **signature scheme** | `X-Hub-Signature` (HMAC-SHA256) | `X-Hub-Signature-256` (HMAC-SHA256) | **`X-Gitlab-Token` (static shared secret, NOT HMAC)** | signature header |

Note the GitLab divergence: `ScmIngress.verifySignature` is per-provider — HMAC for GitHub/Bitbucket, a
constant-time token compare for GitLab.

## 8. Open a pull request → `PullRequestSink` (M2)

The only WRITE in this document that creates a resource rather than commenting on one. Nothing in
the codebase did this before M2 — the reviewer only ever commented on pull requests other people
opened.

> **What is established, and what is not.** The three cloud columns are implemented and covered by
> their `*PullRequestSinkTest`s — but those tests drive WireMock stubs that this repository wrote,
> so they establish what each ADAPTER does, never what the forge does. **No column here has been
> measured against a live API.** The endpoints and field names come from each vendor's
> documentation; the quoted ERROR STRINGS are the least reliable rows in the table, because every
> forge rewords them without notice and none of them is a code you can switch on. Treat a string
> match as a heuristic with a fallback, which is what `GitHubPullRequestSink` does — an unmatched
> 4xx stays a fault rather than being guessed into an outcome. `docs/UNVERIFIED.md` carries this.

| neutral operation | Bitbucket Cloud | GitHub | GitLab | Bitbucket DC |
|---|---|---|---|---|
| **open** | `POST /repositories/{ws}/{slug}/pullrequests` | `POST /repos/{owner}/{repo}/pulls` | `POST /projects/{id}/merge_requests` | `POST /projects/{k}/repos/{slug}/pull-requests` |
| source branch field | `source.branch.name` | `head` | `source_branch` | `fromRef.id` (full ref) |
| target branch field | `destination.branch.name` | `base` | `target_branch` | `toRef.id` (full ref) |
| description field | `description` | `body` | `description` | `description` |
| **number in the response** | `id` | `number` | `iid` (NOT `id`) | `id` |
| **web URL in the response** | `links.html.href` | `html_url` | `web_url` | `links.self[0].href` |
| **find by source branch** | `GET …/pullrequests?q=source.branch.name="X" AND state="OPEN"` | `GET …/pulls?state=open&head={owner}:{X}` | `GET …/merge_requests?source_branch=X&state=opened` | `GET …/pull-requests?at=refs/heads/X&direction=OUTGOING&state=OPEN` |
| **"nothing to propose"** | 400, `"There are no changes to be pulled"` | 422, `"No commits between …"` | 409, `"branch conflicts"` / empty-diff 400 | 409, `"the from and to refs are the same"` |
| **already exists** | 400, names the existing request | 422, `"A pull request already exists for …"` | 409, `"Another open merge request already exists"` | 409, duplicate |

**No adapter matches the already-exists row, and that is deliberate.** It was matched by wording
until a review pointed out the row itself admits the Bitbucket phrasing is unknown ("names the
existing request"), so the guard would simply never fire there — and a genuine race would be
reported as a hard failure. The case is identifiable by BEHAVIOUR instead: on any create refusal
that is not nothing-to-propose, ask the forge whether one exists now. If it does, a race was the
cause whatever the forge called it. The row stays in this table as documentation of what each
forge sends; nothing in the code depends on it.

Four divergences are load-bearing, and each is a trap this repository has paid for in its own form:

1. **GitLab numbers a merge request twice.** `iid` is the per-project number in the URL and in every
   API path; `id` is a global identifier that addresses nothing a human sees. Reading `id` produces a
   number that looks entirely valid and points at another project's merge request. This is why
   `PullRequestRef` names the component `number` rather than `id`.
2. **Bitbucket DC takes FULL REFS, not branch names.** `refs/heads/x`, where the other three take
   `x`. An adapter that passes a bare name gets a 400 that names neither field.
3. **Only GitHub refuses a duplicate.** Bitbucket and GitLab will happily open a second pull request
   from the same source branch. So idempotency cannot be "let the forge decide" — it is
   `findByHead` first, in every adapter, and the port says so.
4. **"Nothing to propose" is a different status on every forge** and on none of them is it an error
   code you can switch on. It is the honest outcome of a run whose agent changed nothing, and each
   adapter maps its own forge's status AND wording to `PullRequestSink.NothingToPropose` so a
   caller never has to know which forge it is talking to.

   **Both halves, and the asymmetry is why.** An unmatched failure degrades safely — it stays the
   forge's own fault, which is what the port promises. A falsely matched one reports a run as "the
   agent changed nothing" when the forge refused for another reason, which is the direction the
   port exists to prevent. The match runs against a 500-character raw body snippet, so without a
   status gate an HTML error page from a proxy in front of a self-hosted forge is scanned by the
   same substring test as a real validation response. For the same reason Bitbucket matches its
   full phrase rather than the two generic words `no changes`.

5. **A pull request is unique per (head, base) PAIR, not per head.** All four forges permit
   `spire/x → main` and `spire/x → develop` open at once, and GitHub's duplicate refusal fires only
   when both match — so a lookup keyed on the head alone is WIDER than the rule the forge enforces.
   It answers a pull request aimed somewhere else, which the caller records as this run's delivery
   while the one that should exist never opens. ADR-040's existing-branch mode makes that reachable
   by design. Every `findByHead` therefore takes both branches and filters on both.

6. **Bitbucket filters through a query LANGUAGE, not named parameters.** A double quote is legal in
   a git refname and `URLEncoder` protects the transport rather than the parser, so a branch name
   carrying one would restructure the clause — `x" OR state="OPEN` widens it to the repository's
   first open pull request. The adapter REFUSES such a name rather than escaping it, because
   Bitbucket's own escaping rule for this language is not something this repository has verified
   and a wrong escape is indistinguishable from none.

**No label.** GitHub and GitLab have label APIs for pull requests; Bitbucket Cloud has none. A
"factory-authored" label would therefore be a mark that exists on two forges out of three — which is
worse than no mark, because a consumer learns to trust it and is then silently wrong on the third.
The mark is a fixed marker at the top of the description instead, written by the orchestrator, and
it is identical on all four.

## People directories (M3 slice 3)

| Forge | Lookup and refresh | Capability |
|---|---|---|
| GitHub | GET /users/{login}; GET /user/{id} | Exact login, then durable numeric ID; visibility depends on selected credential. |
| GitLab | GET /users?username=; GET /users/{id} | Exact username, refuse ambiguous/incomplete replies. |
| Bitbucket Cloud | Workspace members; GET /users/{account_id} | Explicit member selection across bounded pages; workspace/user-read access required. Nicknames are not unique. |
| Jira Cloud | GET /rest/api/3/user/search; GET /rest/api/3/user?accountId= | Explicit candidate selection; Browse users and groups plus user-read access. |
| Jira Data Center | Unsupported person lookup | Existing context-read support does not imply Cloud accountId support. |

Every save repeats resolution through the same configured account, verifies the returned stable
ID, and stores observed labels separately. Identity reads reject redirects to another origin.
Per-forge documentation links and measured-versus-live limits are recorded separately in
UNVERIFIED. No source allowlist inheritance is implied by reusable account person controls.

## Effective repository push permission (M3 slice 4)

| Forge | Read | Binding and interpretation |
|---|---|---|
| GitHub | GET /repos/{owner}/{repo}/collaborators/{username}/permission | Resolve username afresh by stable ID; verify response user.id. Base write/admin allows; read/none refuses; other base roles are unknown. |
| GitLab | GET /projects/{encoded namespace/repo}/members/all/{id} | Verify returned ID and active state. Known 30/40/50 allows; known read levels refuse. Expired membership refuses; unfamiliar shapes/levels are unknown. |
| Bitbucket Cloud | GET /workspaces/{workspace}/permissions/repositories/{slug} | Verify account_id via user read, match UUID and repository full_name across all pages. write/admin allows, read refuses. Caller requires repository-admin access. |

Permission reads pin the configured origin and never try another credential. Unknown, forbidden,
rate-limited, timed-out or incomplete reads refuse; a 404 is not treated as known absence. A
complete Bitbucket list without the actor is known no-write. The permission says general code
write, not that a protected target branch will accept a push. Per-forge live limitations are in
UNVERIFIED; fixture measurements do not establish live token capability.

## External work answers and activity (M3 slice 9)

| Channel | Implemented evidence | Limit |
|---|---|---|
| GitHub native PR review | Re-read named review, complete bounded chronological review history and current open PR/head; stable user ID, human type and measured push permission without DENY | Can answer an open linked LAND gate only; production LAND remains unavailable. Controlled-response tests, no live gate proof |
| GitLab / Bitbucket native PR review | Capability unavailable in the current approval port composition | Visibly disabled; dashboard and tracker remain usable |
| GitHub / GitLab tracker comment | Authenticated created-comment delivery with stable actor ID, source membership and explicit gate/generation/artifact command | Ordinary approving text is not an approval; system/edited GitLab notes are excluded |
| Jira Cloud tracker comment | Authenticated REST v2 comment polling using accountId, timestamp and explicit command | Ignore undated/pre-admission comments; bounded pages report a polling failure when incomplete |
| Jira Data Center tracker comment | Unavailable | Context-read support does not establish Cloud person identity |

SCM takeover adapters use signed actor fields, not commit-author or display text. Repository and
linked PR/branch coordinates must match. Recorded factory/reviewer identities remain authoritative
after rename or rotation; tracker identity is a separate namespace. Unknown actors suspend
conservatively. Gate answers and authorized /fix commands precede generic comment takeover.
No native approval can resume a suspended item. These are automated provider-response proofs;
the separate identity/permission and live activity limits remain in UNVERIFIED.

## Sources
Bitbucket Cloud: developer.atlassian.com/cloud/bitbucket/rest + support.atlassian.com event-payloads ·
GitHub: docs.github.com/rest/pulls · GitLab: docs.gitlab.com/api/merge_requests, /discussions ·
Bitbucket DC: developer.atlassian.com/server/bitbucket/rest.
