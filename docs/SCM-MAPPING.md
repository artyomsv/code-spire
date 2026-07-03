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
work on every provider.** Unified-text providers are parsed with the ported PR-Agent hunk logic; DC's
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

## Sources
Bitbucket Cloud: developer.atlassian.com/cloud/bitbucket/rest + support.atlassian.com event-payloads ·
GitHub: docs.github.com/rest/pulls · GitLab: docs.gitlab.com/api/merge_requests, /discussions ·
Bitbucket DC: developer.atlassian.com/server/bitbucket/rest.
