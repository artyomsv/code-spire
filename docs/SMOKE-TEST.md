# Smoke Test Runbook

**A** stub pipeline, zero external accounts; **B** real Bitbucket Cloud PR (webhook);
**C** real GitHub PR via manual Register PR (no webhook); **D** real GitLab MR via manual Register
PR (no webhook); **E** real GitHub PR via webhook (Tailscale Funnel); **F** real GitLab MR via
webhook (Tailscale Funnel); **G** provider-parity regression script (run the same scenarios on every
SCM); **H** the attention panel; **I** context provisioning across every provider type; **J** operator
authentication; **K** the LLM cost ledger; **L** review archival and retirement; **M** spend caps and
the refused review; **N** conversation-derived findings (`/finding`) on every SCM; **O** the
repository knowledge base — a resolved code snippet reaching the review (P3 rung 1). Do A first — it
validates your local stack in ~2 minutes.

Prerequisites for all modes: JDK 25 (SDKMAN `25.0.3-tem`), Docker running.

---

## Mode A — local demo (stub SCM + stub LLM)

```bash
cp .env.example .env            # set POSTGRES_PASSWORD to any dev-only value
echo "SPIRE_SCM_STUB=true" >> .env   # Mode A only: stub the SCM adapters (dev otherwise defaults to real)
docker compose up -d            # Postgres 18.4 :34432 + Redpanda v26.1.12 :34092
# wait for health:
docker ps --filter name=spire   # both should show (healthy)

# terminal 1 — the brain + dashboard
./gradlew :spire-orchestrator:quarkusDev
# terminal 2 — the worker
./gradlew :spire-review-worker:quarkusDev
```

Open **http://localhost:34080**, flip the **Review-mode** slider in the sidebar to **active**
(a fresh DB seeds to *observe*), then press **Simulate PR**.

**Expected:** the timeline animates through
`PullRequestEventReceived -> ReviewRequested -> FetchDiff -> DiffFetched -> GatherContext ->
ContextAssembled -> GenerateReview -> ReviewGenerated -> PostComments -> CommentsPosted ->
ReviewCompleted` (green). The worker log shows `STUB summary comment ...` / `STUB inline comment ...`.

That's the whole choreography over real Kafka — only the SCM and LLM are stubbed.

---

## Mode B — real Bitbucket Cloud PR + real LLM

### Stage 0 — observe-only first contact (no interaction)

Before the bot posts anything, verify the integration in a safe posture: receive
the PR webhook, register the review, and do **nothing** else — no diff fetch, no
LLM call, no comments. No env var — a fresh DB already seeds to **observe**; the
sidebar **Review-mode** slider is the live control.

- `observe` registers each PR event — visible on the dashboard as
  `PullRequestEventReceived → ReviewRequested → ReviewObserved` — but emits no work.
- The **PR-author allowlist** is per-provider (Settings → Providers → Authors), so
  only listed authors are registered; everyone else is skipped with a
  `PullRequestSkipped` note. Matches account id OR username; empty = everyone.

In observe mode the **worker never runs and no app password / LLM key is needed** —
only the gateway + orchestrator. The orchestrator logs the posture at boot:
`Review policy: mode=OBSERVE (register only, no diff/LLM/comments), author-allowlist=N author(s)`.

```bash
./gradlew :spire-orchestrator:quarkusDev
./gradlew :spire-gateway:quarkusDev
```

Register the webhook (step 4 below), open a PR as an allowlisted author, and
confirm it lands on the dashboard at `:34080` with **no** Bitbucket comment
posted. Once that works, flip the **Review-mode** slider to `active`, add the app
password + LLM key, start the worker, and continue with the full review below.

### 1. Bitbucket bot account (one-time)

1. Create (or pick) the **bot account** — the identity that posts all reviews.
2. As the bot: *Personal settings -> App passwords -> Create* with scopes
   **Pull requests: Write** and **Repositories: Read**.
3. You do **not** need the bot's `account_id` at all — register the provider in
   Settings → Providers, leave "Bot account id" blank, and it is resolved from the
   token on save (which also validates the token). The same resolved id drives the
   orchestrator's self-loop guard, so nothing reads it from env anymore.
4. Give the bot access to a **sandbox test repository** (read + comment is enough).

### 2. LLM

Any OpenAI-compatible endpoint works. Two easy options:
- **OpenAI:** base url `https://api.openai.com/v1`, your API key, a model name.
- **Local Ollama (zero cost):** `ollama serve` + `ollama pull <model>`, base url
  `http://localhost:11434/v1`, api key `ollama` (any non-blank value), model = the pulled model.

### 3. Tunnel (webhooks must reach the gateway)

```bash
cloudflared tunnel --url http://localhost:34081     # or: ngrok http 34081
```
Note the public https URL it prints.

### 4. Register the webhook

First in Code Spire (*Settings -> Webhooks -> Add*): provider `bitbucket-cloud`,
scope `Repository`, target `workspace/repo`, and a secret you generate
(e.g. `openssl rand -hex 24`). Save — it shows the routing path
`/webhooks/bitbucket-cloud/<key>`.

Then on the TEST repo (*Settings -> Webhooks -> Add*):
- URL: `https://<tunnel-host>/webhooks/bitbucket-cloud/<key>`
- Secret: the **same** value you entered in the UI above
- Triggers: Pull request **Created**, **Updated**, **Comment created**, **Merged**, **Declined**

### 5. `.env`

Append to your Mode-A `.env` (drop the `SPIRE_SCM_STUB` line — this is a real SCM):
```bash
SPIRE_LLM_PROVIDER=openai-compatible

SPIRE_LLM_BASE_URL=<endpoint>/v1
SPIRE_LLM_API_KEY=<key>
SPIRE_LLM_MODEL=<model>
```
Missing keys fail the affected service at startup naming the exact key — that's intended.

### 6. Run all three services

```bash
./gradlew :spire-orchestrator:quarkusDev    # :34080 dashboard
./gradlew :spire-gateway:quarkusDev         # :34081 webhook edge
./gradlew :spire-review-worker:quarkusDev   # :34082
```
(The dev simulator returns 404 in this mode by design — synthetic events never enter a real pipeline.)

### 7. The test

1. In the sandbox repo, push a branch with a small code change and **open a PR**.
2. Watch the dashboard timeline; within ~LLM-latency the PR gets **inline comments on changed
   lines + one summary comment**, posted by the bot account.
3. **Update the PR** (push another commit) — a new review runs for the new commit; the old run is
   superseded (no stale comments).
4. **Bitbucket redelivery test:** repo *Settings -> Webhooks -> View requests -> Resend* on the
   `pullrequest:created` delivery — no duplicate comments may appear (idempotency).
5. **Merge or decline the PR** — an in-flight review (if any) cancels; timeline shows `ReviewCancelled`.

### Known v1 limits (expected, not bugs)

- Draft/WIP PRs and MRs are skipped until marked ready, on **all three** SCMs (GitHub
  `draft`, GitLab `draft`/`Draft:`/`WIP:` title prefix, Bitbucket draft flag), unless
  `SPIRE_REVIEW_DRAFT_PRS=true`.
- Bitbucket reconciliation **resolves** the thread like GitHub and GitLab (comment-resolve API,
  added 2026-07-25) — a fixed finding is both replied to and closed. Note the SCM's own *Outdated*
  badge is separate: it only means the code under the comment changed.
- Bitbucket inline comments are **single-line** — a finding spanning multiple lines anchors to
  its first line rather than a range (GitHub and GitLab both support multi-line ranges).
- A PR whose diff exceeds the provider's diff-generation limit fails with an explicit "too large to
  review" error instead of the raw HTTP response.
- A transient SCM/LLM failure auto-retries the pipeline up to `spire.review.max-attempts` (default 3);
  the timeline shows `retry:<phase>` and the metadata `Attempt` climbs. Only once the budget is spent
  (or the failure is permanent) does the review go to `failed` — then push a new commit to restart.
- The dashboard is unauthenticated (OIDC lands in P2) — don't expose :34080 through the tunnel.

### Troubleshooting

| Symptom | Check |
|---|---|
| Webhook shows 401 in Bitbucket's request log | secret in `.env` != secret in the webhook config |
| 202 in gateway log but nothing on the dashboard | broker: `docker exec spire-redpanda rpk topic list` should show `cs.*`; orchestrator/worker logs |
| Run stuck at `GenerateReview` | worker log — LLM endpoint/key/model; Ollama: is the model pulled? |
| `retry:<phase>` then `failed` | transient failures exhausted the retry budget — check the worker log for the root cause; raise `spire.review.max-attempts` or push a new commit |
| Dead letters | `docker exec spire-redpanda rpk topic consume cs.dlq --num 5` |
| Service refuses to start | it names the missing config key — set it in `.env` |

### Cleanup

Delete the webhook + tunnel; bot comments can stay or be deleted in the PR UI. Local state:
`docker compose down -v` wipes the event store and topics.

---

## Mode C — real GitHub PR, active review, **no webhook** (manual Register PR)

The fastest way to prove a full active review against a real SCM without setting up a
tunnel or webhook. It drives the identical pipeline as Mode B — diff → LLM → inline +
summary comments — but the PR is registered manually through the dashboard instead of
arriving on a webhook. Works for any registered provider (used here with GitHub).

**Gateway is not needed** — the orchestrator's `POST /api/reviews/register` publishes the
same `PullRequestEventReceived` the gateway webhook would, onto the same `cs.integration`
topic. Minimal set: Postgres + Redpanda + **orchestrator + worker**.

### 1. One-time prerequisites

1. Register a **GitHub provider** in Settings → Providers (workspace = repo owner, e.g.
   `artyomsv`) with a token scoped **Contents: Read** + **Pull requests: Read and write**.
   Leave "Bot account id" blank — it is resolved from the token on save (`IdentitySource`).
2. In Settings → LLM (ADR-018): first **add a model** (e.g. name `gpt-4o`, input `$2.50` / output
   `$10.00` per 1M tokens — from OpenAI's pricing page), then register an **LLM provider**: type
   `openai`, base URL `https://api.openai.com/v1`, your API key, and pick the model from the dropdown.
   The key is validated on save and stored encrypted; mark the provider the **default**. No
   `SPIRE_LLM_*` env vars. The model's pricing is what shows the per-review **cost** on the dashboard.
3. In `.env`, only one mode flag is needed:
   ```bash
   SPIRE_LLM_PROVIDER=registry  # stub|registry mode flag (NOT credentials); registry = use the UI
   ```
   The SCM already defaults to real (no env var). Review mode is the **sidebar slider** — a fresh
   DB seeds to `observe`; flip it to `active` from the dashboard when ready (no restart).
   `SPIRE_ENCRYPTION_KEYSET` must be the **same** value the orchestrator uses — the worker decrypts
   the brokered per-command SCM + LLM credentials with it (ADR-015/ADR-018).
4. The PR author must pass the provider's allowlist (empty allowlist = everyone).

### 2. Run the two services

```bash
./gradlew :spire-orchestrator:quarkusDev    # :34080 dashboard + register endpoint
./gradlew :spire-review-worker:quarkusDev   # :34082
```
The orchestrator logs the posture at boot: `Review policy: mode=active`.

### 3. Register a PR and watch it review

Open a PR on the sandbox repo with a small **code** change (a text-only diff gives the LLM
nothing to anchor inline comments to — the summary still posts). Then either:

- **UI:** open http://localhost:34080 → **Register PR** (top bar) → paste the PR URL
  (auto-fills owner / repo / PR #) → **Register**, or
- **curl:**
  ```bash
  curl -s -X POST http://localhost:34080/api/reviews/register \
    -H 'Content-Type: application/json' \
    -d '{"workspace":"<owner>","slug":"<repo>","pr":<number>}'
  # → {"reviewId":"review::<owner>/<repo>#<number>", ...}
  ```

**Expected:** within ~LLM latency the review reaches `status=completed` (stage 6) on the
dashboard, and the PR gets **inline comments on the changed lines + one summary comment**,
posted by the token owner. Inline findings whose line is not on a diff line are folded into
the summary as "unanchored" rather than dropped (GitHub rejects out-of-diff inline anchors).

### Verify from the CLI

```bash
gh api repos/<owner>/<repo>/pulls/<number>/comments --jq 'length'     # inline count
gh api repos/<owner>/<repo>/issues/<number>/comments --jq 'length'    # summary count
```

### Cleanup

Flip the **Review-mode** slider to observe (or stop the services) to return to a no-write posture.
Bot comments can stay or be deleted in the PR UI.

## Mode D — real GitLab MR, active review, **no webhook** (manual Register PR)

Identical to Mode C — the same manual-register pipeline (diff → LLM → inline + summary), the
same minimal service set (Postgres + Redpanda + orchestrator + worker), the same review-mode
flags. Only the SCM-provider specifics differ. Works against `gitlab.com` and any self-managed
GitLab (`baseUrl` drives it — e.g. a company `https://git.example.com/api/v4`).

### GitLab-specific prerequisites

1. Register a **GitLab provider** in Settings → Providers:
   - **workspace** = the top-level group (for `gitlab.com/<group>/<sub>/<project>`, use `<group>`;
     the sub-group + project become the slug automatically),
   - **base URL** = `https://gitlab.com/api/v4` (or `https://<self-managed-host>/api/v4`),
   - **token** = a Personal (or Project/Group) Access Token with the **`api`** scope,
   - Leave "Bot account id" blank — it is resolved from the token on save (`IdentitySource` → `GET /user`).
2. LLM provider + review-mode flags: exactly as Mode C, steps 2–4.

### Register an MR and watch it review

Open an MR on the sandbox project with a small **code** change, then Register PR (or curl) with
the MR URL:

- **UI:** **Register PR** → paste `https://gitlab.com/<group>/<project>/-/merge_requests/<iid>`
  (the backend `/resolve` auto-fills group / project / MR # and shows which provider will handle
  it) → **Register**.
- **curl:** post the resolved fields (`workspace` = group, `slug` = `[sub-group/]project`,
  `pr` = the MR `iid`) to `POST /api/reviews/register`, same shape as Mode C.

**Expected:** `status=completed`, and the MR gets **inline discussion comments on the changed
lines + one summary note**, posted by the token owner. GitLab needs all three diff SHAs
(`base`/`start`/`head`) to anchor an inline position — the adapter carries them; findings whose
line is off the diff fold into the summary rather than being dropped (same as GitHub).

### Verify from the CLI

```bash
# encode the full project path (group%2F…%2Fproject); <iid> is the MR number
curl -s -H "PRIVATE-TOKEN: $TOKEN" \
  "https://gitlab.com/api/v4/projects/<enc-path>/merge_requests/<iid>/discussions" --output - | grep -c '"id"'
```

### Cleanup

Same as Mode C — flip the **Review-mode** slider to observe (or stop the services); MR discussions
can stay or be resolved/deleted in the GitLab UI.

## Mode E — real GitHub PR via **webhook** (Tailscale Funnel)

Proves the full auto-register loop: open a PR → GitHub delivers a webhook → the gateway verifies
it and publishes the same `PullRequestEventReceived` the manual path does → diff → LLM → inline +
summary — **no manual Register PR**. This is Mode C plus a real webhook edge, so it needs the
**gateway** (and a public URL for GitHub to reach it).

One endpoint serves every repository; a per-repo **key** in the path routes the delivery, and a
per-repo **HMAC secret** (stored encrypted under the dedicated webhook keyset) authenticates it.

### 1. One-time prerequisites

1. Provider + LLM: exactly as **Mode C**, steps 1–3 (register the GitHub provider, an LLM model +
   provider, `SPIRE_LLM_PROVIDER=registry`). Review mode is the sidebar slider — flip to `active` when ready.
2. **Webhook keyset (gateway only):** generate a **second** Tink keyset (distinct from the master
   one) and set it for the **gateway** — it owns the webhook registry and encrypts/decrypts its own
   secrets:
   ```bash
   SPIRE_ENCRYPTION_WEBHOOK_KEYSET=<base64 Tink keyset>   # NOT the master keyset
   ```
   The gateway holds **only** this keyset and a DB role scoped to its own `gateway` schema, so the
   internet-facing edge can never decrypt (or even read) the SCM/LLM API-token registry. The
   orchestrator never sees webhook secrets. (Also ensure the scoped `gateway` DB role exists —
   `GATEWAY_POSTGRES_*` in `.env`; a fresh `docker compose up` provisions it.)
3. **Register the repository webhook:** Settings → **Webhooks** → Add → choose the provider type, enter
   `owner/repo`, and set a **secret** (any strong random string — you'll paste the same one into
   GitHub). Save. The row shows the **Payload URL path** `/webhooks/github/<key>`.

### 2. Tunnel — Tailscale Funnel (stable URL)

```bash
tailscale funnel 34081          # exposes the gateway on https://<host>.ts.net
```
The `https://<host>.ts.net` URL is stable across sessions, so you configure GitHub once. (Any HTTPS
tunnel works — cloudflared, ngrok — but their URLs change per run.)

### 3. Configure the GitHub webhook

Repo → Settings → Webhooks → Add webhook:
- **Payload URL:** `https://<host>.ts.net/webhooks/github/<key>` (the path from step 1.3)
- **Content type:** `application/json`
- **Secret:** the secret from step 1.3
- **Events:** "Let me select" → **Pull requests** and **Issue comments** — a `/review` comment
  forces a full re-run of the PR's latest commit; any other plain top-level PR comment starts (or
  continues) a conversation in the summary-comment thread

GitHub sends a `ping` on save → the gateway returns **204** (accepted, nothing published).

### 4. Run all three services

```bash
./gradlew :spire-orchestrator:quarkusDev    # :34080 dashboard + registry
./gradlew :spire-gateway:quarkusDev         # :34081 webhook edge (behind the funnel)
./gradlew :spire-review-worker:quarkusDev   # :34082
```

### 5. The test

Open (or reopen, or push to) a PR on the repo as an allowlisted author. GitHub delivers
`pull_request` → the gateway returns **202** and the review appears on the dashboard, then
progresses diff → LLM → **inline + summary comments** — with no manual step.

**Iterate without new PRs:** GitHub → repo → Settings → Webhooks → your hook → **Recent
Deliveries** → **Redeliver** replays a captured delivery against your local gateway. When your
laptop is off, deliveries fail and can be redelivered later.

### Troubleshooting

| Symptom | Cause |
|---|---|
| GitHub shows **401** in Recent Deliveries | the secret in Settings → Webhooks ≠ the secret in GitHub |
| GitHub shows **404** | wrong/rotated key in the payload URL, or the webhook row is disabled |
| GitHub shows **400** | the delivery's repo ≠ the registered `owner/repo` (wrong key pasted into another repo) |
| **202** but nothing on the dashboard | PR author not in the provider allowlist, or the Review-mode slider is on observe |

### Cleanup

Delete the GitHub webhook + stop the funnel; remove the row in Settings → Webhooks (its key stops
working immediately). Flip the **Review-mode** slider to observe to return to a no-write posture.

## Mode F — real GitLab MR via **webhook** (Tailscale Funnel)

The GitLab equivalent of Mode E: open (or update) an MR → GitLab delivers a webhook → the gateway
verifies it and publishes the same `PullRequestEventReceived` the manual path (Mode D) does → diff
→ LLM → inline discussions + summary note — **no manual Register PR**. Same shared registry edge,
same `RegistryWebhookEdge` resolve → verify → translate → scope → publish tail as GitHub/Bitbucket —
only the verification scheme and the payload shape differ.

### 1. One-time prerequisites

1. Provider + LLM: exactly as **Mode D**, step 1 (register the GitLab provider) and **Mode C**
   step 2 (LLM model + provider, `SPIRE_LLM_PROVIDER=registry`). Review mode is the sidebar slider.
2. **Webhook keyset (gateway only):** the same `SPIRE_ENCRYPTION_WEBHOOK_KEYSET` as Mode E — reuse
   it if the gateway is already configured from Mode E; otherwise generate and set it per Mode E
   step 1.2.
3. **Register the repository webhook:** Settings → **Webhooks** → Add → provider `gitlab`, enter
   the project path (`group[/subgroup]/project`), and set a **secret** — unlike GitHub/Bitbucket
   this secret is **not** used to compute a signature: GitLab sends it back verbatim in the
   `X-Gitlab-Token` header, so the value itself *is* the shared secret (constant-time compared,
   never an HMAC). Save. The row shows the **Payload URL path** `/webhooks/gitlab/<key>`.

### 2. Tunnel — Tailscale Funnel (stable URL)

```bash
tailscale funnel 34081          # exposes the gateway on https://<host>.ts.net — same as Mode E
```

### 3. Configure the GitLab webhook

Project → Settings → Webhooks → Add new webhook:
- **URL:** `https://<host>.ts.net/webhooks/gitlab/<key>` (the path from step 1.3)
- **Secret token:** the secret from step 1.3 (pasted verbatim — this is compared, not hashed)
- **Trigger:** **Merge request events** and **Comments** (note events) — a `/review` note forces a
  full re-run of the MR's latest commit; any other MR note starts (or continues) a conversation
- **SSL verification:** enabled (the funnel presents a valid cert)

GitLab has no ping-on-save the way GitHub does; the row's delivery log fills in once the first real
event (or a manual **Test** send) arrives.

### 4. Run all three services

```bash
./gradlew :spire-orchestrator:quarkusDev    # :34080 dashboard + registry
./gradlew :spire-gateway:quarkusDev         # :34081 webhook edge (behind the funnel)
./gradlew :spire-review-worker:quarkusDev   # :34082
```

### 5. The test

Open (or update, or comment on) an MR on the sandbox project as an allowlisted author. GitLab
delivers `merge_request` → the gateway returns **202** and the review appears on the dashboard,
then progresses diff → LLM → **inline discussions + summary note** — with no manual step. A draft
MR (or a `Draft:`/`WIP:` title) is skipped until it is marked ready, same policy as GitHub (unless
`SPIRE_REVIEW_DRAFT_PRS=true`).

### Troubleshooting

| Symptom | Cause |
|---|---|
| GitLab shows **401**-equivalent (delivery marked failed) | the secret in Settings → Webhooks ≠ the **Secret token** pasted into GitLab |
| **404** | wrong/rotated key in the payload URL, or the webhook row is disabled |
| **400** | the delivery's project ≠ the registered path (wrong key pasted into another project) |
| **202** but nothing on the dashboard | MR author not in the provider allowlist, or the Review-mode slider is on observe |

### Cleanup

Delete the GitLab webhook + stop the funnel; remove the row in Settings → Webhooks (its key stops
working immediately). Flip the **Review-mode** slider to observe to return to a no-write posture.

## Conversation + reconciliation (all real modes)

Both GitLab (Mode D or F) and Bitbucket (Mode B) now carry the same two conversational
capabilities as GitHub — implemented per-adapter via `ThreadSource`, so `FollowUpWorker`,
`ConversationSaga`, and `ReviewWorker` are unchanged; only the SCM adapters differ.

**Conversation:** reply under a bot inline finding (a GitLab discussion note or a Bitbucket PR
comment), or leave a plain top-level MR/PR comment. Within ~LLM latency the bot answers in-thread
(the "smart 1:1" scope — it stays quiet in a multi-party thread unless @-mentioned). Verify: the
reply appears nested under the finding's discussion (GitLab) or comment thread (Bitbucket), or in
the summary-comment/note thread for a top-level comment.

**Reconciliation:** push a follow-up commit that fixes one finding and leaves another open — a
re-review runs against the incremental diff since the prior reviewed commit.
- **GitLab:** the fixed finding's discussion is **resolved** and the still-open finding gets a
  reply; the summary **note** is updated in place.
- **Bitbucket:** the fixed finding's thread is **resolved too** (`POST .../comments/{id}/resolve`) and
  the summary **comment** is updated in place. A thread a human resolved first is left alone
  (`ALREADY_RESOLVED`, no reply).
- Both: the still-open finding always gets a reply (`STILL_OPEN`), whether or not the thread could
  be resolved.

A `RESOLVED` verdict whose thread cannot be found (deleted, or the finding was re-posted under a new
id) degrades to reply-only and reports `resolved: false` — it never claims success it didn't achieve,
so `threadOutcomes` in the `CommentsPosted` event is the honest record of what happened.

**Bitbucket compare direction — settled (2026-07-25).** Bitbucket's compare-diff spec is
`{source}..{destination}` with additions attributed to the source, and
`BitbucketCloudDiffSource.fetchCompareDiff` calls it as `{head}..{base}`. This was previously only
verified against the REST docs (see `BitbucketReconciliationTest.fetchCompareDiffUsesTheTwoDotSpec`);
it is now confirmed live: across four reconciliation rounds on a real workspace every verdict read the
change in the correct direction (a fix was reported as fixed, an untouched finding as `UNCHANGED`, a
partial fix as `STILL_OPEN` naming exactly what remained). A reversed diff would have inverted those
notes. No change needed. If a future run ever shows inverted reasoning, the remedy is to swap the
argument order — `head + ".." + base` → `base + ".." + head`.

## Mode G — provider parity (run the SAME script on every SCM)

Regression script for "do the providers behave alike?". Open **one PR per provider with identical file
content** and run S1–S11 **in this order on each** — the order matters: the conversation scenarios must
run before the fix commits, which change the code and resolve threads.

Expect the *finding counts to differ* between the PRs (LLM non-determinism); that is not a parity
failure. What must match is the behaviour. Grouping differs too: one provider may fold two defects into
a single finding that another reports separately — compare the set of defects found, not the count.

**Use FRESH PRs.** Resuming an older review re-tests whatever state it accumulated; several scenarios
(S5's turn counter, S9/S10's reconciliation) only mean anything from a clean start.

**Prep.** Every provider registered and **enabled** (a shared workspace name across SCMs is fine and
worth testing — resolution disambiguates by the review's stored SCM type). Tunnel up, webhooks
registered. **Do not rename or move the file** before S10's last round: mixing a rename in earlier
muddies every verdict after it, because a changed path is one more thing each later verdict could be
reacting to.

> **Corrected 2026-08-30.** This paragraph used to say a rename "churns finding identity, a separate
> known limitation (`techdebt/`)". That claim was wrong twice over: the cited entry does not exist,
> and `CLAUDE.md` separately recorded a 2026-07-26 pass where a 100%-similarity rename did *not*
> churn identity — so the runbook and the status notes contradicted each other and nobody knew which
> held. `RenameTest` in `spire-e2e` now decides it against a real GitLab: the findings follow the
> file to its new path, nothing reports `SUPERSEDED`, and no defect returns as a new finding. The
> reason to keep the rename last is ordering hygiene, not a defect.

**Before you start, make sure the running services actually contain the code you think they do.**
`docker compose restart` does NOT pick up host edits — the dev containers have no source bind-mount, so
source comes from the image and is only synced by a live `docker compose watch`. After any code change:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build orchestrator worker
# then verify, don't assume:
docker exec spire-orchestrator-dev grep -c "<a string from your change>" /workspace/<path>
```

A restart-instead-of-rebuild produced a false "the fix is live" during the 2026-07-26 pass and cost a
full diagnosis cycle.

Diagnosis one-liners for any failure — strip the ANSI colour codes or the patterns won't match:

```bash
docker logs <orchestrator> --since 5m 2>&1 | sed 's/\x1b\[[0-9;]*m//g' \
  | grep -iE "Answering|declined|Turn cap|Follow-up|skipped"
docker logs <worker> --since 5m 2>&1 | sed 's/\x1b\[[0-9;]*m//g' \
  | grep -iE "Staying|resolve|reply|turn-cap|WARN|ERROR"
docker logs <gateway> --since 5m 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -iE "Rejected|webhook"
```

**When the bot goes silent, check the plumbing before the policy.** A dead tunnel, an expired
quick-tunnel URL and a rejected webhook secret all look *identical* to a legitimate policy decline from
the dashboard: nothing arrives, so nothing is logged on our side and the only record is in the
provider's own webhook-delivery UI. Both happened during the 2026-07-26 pass. In order:

1. `docker compose ps` — is everything up?
2. `docker logs <tunnel> | grep -i trycloudflare | tail -1` — the quick tunnel mints a **new hostname on
   every start**, so any restart invalidates all registered webhooks.
3. `docker logs <gateway> | grep -i Rejected` — a rejected signature means the URL reached us and the
   secret didn't match.
4. Only then read the `ConversationSaga` decision line, which names every factor it weighed.

**Editing a GitLab webhook's URL silently clears its secret token.** GitLab never redisplays the token,
so saving the form after a URL change blanks it and every later delivery is rejected. Re-issue via
Settings → Webhooks → **Rotate secret** and paste it back into GitLab's *Secret token* field. Expect to
do this every time the tunnel URL changes.

| # | Action | Expected on every provider |
|---|---|---|
| S1 | Open the PR | Inline comment per finding + exactly one summary comment; `findings` == inline count; PR badge **Open**; no `ReviewFailed` |
| S3 | Reply under one finding (no @-mention) | Bot answers in the same thread; code arrives in a ```` ``` ```` fence and renders as code; the row shows `responding…` without breaking the table layout. The reply must answer THIS thread's question — it must not survey the PR's other findings, which own their own threads |
| S2 | *(after S3)* Expand that finding's conversation in the UI | Full untruncated text (`GET /api/reviews/{ws}/{slug}/{pr}/threads/{ref}` → 200), not the ≤160-char preview. **Runs after S3 on purpose:** there is nothing to expand until a conversation exists, and the findings card shows a finding's own text in full with no expander |
| S4 | Reply to **the bot's answer** (twice) | Bot keeps answering, and the answer reflects the whole thread. Provider-specific ref in the log — GitHub and GitLab normalize to the thread root, Bitbucket keys off the answer's id; all must resolve to one conversation. On Bitbucket use the Reply button **on the bot's answer**, which is the case that used to split the conversation |
| S5 | Turn cap — keep replying until `priorTurns == cap`, then reply once more | Turns accumulate on the conversation **root**: `turn_count` on that row rises per turn (a per-answer row must not reset it). At the cap the bot posts a **hand-off notice** and `turn_count` stops rising; the next plain reply posts **nothing more** (one notice per thread); an @-mention afterwards gets a **real answer**, since a mention overrides the cap. Silence with no notice is a failure — check the tunnel before the policy |
| S6 | New thread on an **unflagged** line, @-mention the bot | Bot answers even though it isn't a finding thread (`mentioned=true` in the log). GitHub and GitLab render `@<username>`; Bitbucket inserts `@{account_id}` from its picker — all must match. It does **not** create a finding (by design). Known gap: the thread has no anchor in `AuthorReplied`, so the UI files it under *General discussion* rather than at its line |
| S7 | Plain top-level PR comment | Answered in the **summary** thread |
| S8 | Post `/review` | New run on the same commit; summary comment **updated in place**, never duplicated |
| S9 | Fix **one finding fully**, push (same path) | Verdict `RESOLVED`; thread gets a closing reply and is **resolved on the SCM** (`resolved: true` in `threadOutcomes`); untouched findings `UNCHANGED`; a partially-fixed finding is `STILL_OPEN` with a note naming what remains |
| S10 | Fix the rest over several commits — deliberately leave some unfixed and introduce new issues | Each round: real fixes → `RESOLVED` + `resolved: true`; unfixed → `STILL_OPEN`; new issues → new findings with their own threads, reconciled in later rounds. Ends at `openFindings: 0`, `openBlockers: 0`, `status: completed`. Save the rename/move for the final round: findings must follow the file to its new path and resolve there, never come back as new, and never report `SUPERSEDED` (the code moved, it did not disappear) |
| S11 | Merge one PR, decline/close another; leave one open as a control | PR badge flips **MERGED** / **CLOSED**, independent of the review status (stays `completed`); the review history records a `PullRequestClosed` row; **no further review runs** (no new `ReviewRequested`/`CommentsPosted`). The untouched PR staying `OPEN` proves the change came from the action, not from something ambient |

**Where providers legitimately differ** (not failures): Bitbucket inline findings are single-anchor
(GitHub/GitLab render a multi-line range); mention syntax (`@{account_id}` vs `@login`); the resolve
mechanism (GitHub GraphQL review threads, Bitbucket the comment-resolve API, GitLab discussion resolve)
— but all three must end **resolved**. An SCM's own "Outdated" badge is orthogonal to resolution: it
means the code under the comment changed, and a thread can be both Outdated and Resolved.

**Verify from the read model** rather than the UI alone — the durable record answers most questions
without touching Kafka, and `rpk topic consume` with `-o end` **tails and never returns**, so bound it:

```sql
-- verdicts, per-thread outcomes, and whether a resolve really landed on the SCM
SELECT type, thread_ref, detail FROM orchestrator.review_event
 WHERE review_id = 'review::<ws>/<slug>#<pr>' AND type IN ('ThreadResolved','ThreadReplied')
 ORDER BY seq;
SELECT thread_ref, line, is_ours, is_summary, resolved, turn_count, root_ref
  FROM orchestrator.review_thread WHERE review_id = '...' ORDER BY seq;
```

`ThreadResolved` is written only when the adapter reported `resolved: true`, so its presence is proof the
SCM-side resolve succeeded — a `RESOLVED` verdict that degraded to reply-only appears as `ThreadReplied`
instead. To read the raw command on the bus (e.g. to confirm a field was populated rather than inferring
it from behaviour), bound the consume:

```bash
docker exec <redpanda> rpk topic consume cs.commands --offset -3 --num 3 --format '%v\n'
```

**Last full pass:** 2026-07-26 — GitHub + GitLab + Bitbucket Cloud, 11/11 behaviourally on all three.
Exercised every reconcile verdict except `ACKNOWLEDGED`; `SUPERSEDED` correctly never fired. The pass
found three defects, all fixed with tests: a silent turn cap, GitLab's compare diff parsing to zero
files (which made every `STILL_OPEN` downgrade to `UNCHANGED` on that provider alone), and follow-up
replies surveying findings that belonged to other threads.

## Mode H — attention panel

Each check should make a bell row appear, and undoing it should make the row disappear with no
dismissal.

1. **No usable default LLM provider.** Settings → LLM, disable the default provider. Expect a
   red badge with `LLM_DEFAULT_MISSING`. Re-enable it; the row goes.
2. **Rejected credential.** Settings → Providers, edit a provider's token to a wrong value and
   press Check. Expect `CREDENTIAL_REJECTED` naming that provider. Restore the token and press
   Check; the row goes.
3. **Rejected webhook deliveries.** Change a registration's secret at the provider without
   rotating it here, then push a commit. Expect `WEBHOOK_DELIVERIES_REJECTED` naming the repo.
   Rotate the secret, re-save it at the provider and push again; the row goes on the next
   verified delivery.
4. **Stuck review.** Stop the review worker and push a commit. After
   `SPIRE_ATTENTION_STUCK_MINUTES` expect `REVIEW_STUCK`. Restart the worker and let the review
   finish; the row goes.
5. **Unreachable gateway.** Stop the gateway container. Expect a blocking `GATEWAY_UNREACHABLE`
   row **and** the orchestrator's own rows still listed. Restart it; the row goes.
6. **Clean system.** With everything configured and healthy, expect **no badge at all** — not a
   green tick.

## Mode I — context provisioning across every provider type

Any of the review modes above pulls linked context into the prompt once a context provider is
registered — additive, so no provider registered means reviews run exactly as before (empty
context). This mode proves that provisioning works during a real review, against a real instance,
for all four provider types (Jira, Confluence, GitHub Issues, GitLab Issues) on all three SCMs
(GitHub, GitLab, Bitbucket). Code-complete plus WireMock is not the bar for the two newest
providers — this is their first contact with a real host.

Scenarios are numbered **I-1 … I-18** so results can be reported back by id. The **minimum set** is
one per SCM plus the guard — **I-10** (GitHub), **I-13** (GitLab), **I-17** (Bitbucket) and
**Part 3**; the rest widen coverage.

### What each observable actually proves — read this first

Context is deliberately hard to see, and two of the three places you might look show less than you
would expect. Know which is which before interpreting a run.

| Observable | Where | Proves | Does **not** prove |
|---|---|---|---|
| `ContextAssembled` row | spire-ui review detail, event list | The fan-out ran and finished for this review | That anything was found — it is emitted with an empty context too |
| Stage reaches **Context** then **Review** | spire-ui review detail | The pipeline advanced past context | Anything about content |
| `worker.context_blob` row, `size_bytes > 0` | Postgres | **≥1 real item was resolved, fetched and persisted** for this review | *Which* provider produced it |
| `ContextRequested` / `ContextContributed` rows | orchestrator dashboard `:34080` only | A fan-out happened, a provider answered | Nothing else — both rows carry an **empty reviewId and empty detail**, so no source name, no item count, no attribution to a review |
| **Test** result (Settings → Context) | spire-ui | The exact `ContextItem` (kind/title/body/uri) that provider would inject, with real credentials against the real host | That a *review* would resolve the same reference (no PR/MR behind Test) |
| The bot's review text | The PR/MR | Nothing. A weak signal at best | Record as an observation, never as evidence |

Two consequences that shape every scenario below:

1. **Attribution comes from the experiment, not the screen.** Nothing at runtime says "GitHub Issues
   contributed 2 items". So **enable exactly one context provider at a time** — then a
   `worker.context_blob` row for that review can only have come from that provider.
2. **The blob row is the verdict.** `size_bytes` is the *plaintext* length, written only when at
   least one item was assembled. No row ⇒ empty context. It needs no decryption:

```bash
docker exec -it spire-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT review_id, size_bytes, created_at FROM worker.context_blob ORDER BY created_at DESC LIMIT 5;"
```

Shorthand used below: **CONTRIBUTED** = a `worker.context_blob` row for that reviewId with
`size_bytes > 0`. **NOT CONTRIBUTED** = no row for that reviewId (with one provider enabled).

### Setup

1. Stack up (`docker compose up -d`), all three services running, a default LLM provider configured.
2. **Review-mode slider → `active`.** `observe` registers the PR and stops — no diff, no context, no
   LLM. Nothing in this mode works in observe.
3. Because the mode is active, **the bot posts real comments on the PRs you use.** Pick repositories
   where that is acceptable.
4. Register providers in Settings → **Context**, each with its own token:

| Type | baseUrl | authKind | Project keys field means |
|---|---|---|---|
| `jira` | `https://<site>.atlassian.net` (DC: your host) | `basic` + account email (DC: `bearer` PAT) | Jira project keys (`ACME`) — lets a bare number resolve |
| `confluence` | same site root | as Jira | Space keys |
| `github-issues` | `https://api.github.com` (GHES: `.../api/v3`) | `bearer` only | `owner` or `owner/repo` allow-list |
| `gitlab-issues` | `https://gitlab.com` (or self-managed host) | `bearer` only (`read_api` scope) | `group` or `group/project` allow-list, prefix-matched |

`basic` is **rejected on save** for the two `*-issues` types (both APIs are bearer-only) — a 400,
not a silent accept. Save also validates the credential up front, so a bad token cannot be stored.

5. Run every row's **Check**. Each must return the token owner (`/user`, `/api/v4/user`, and each
   Atlassian product's own identity call).

**Where references are read from.** Extraction runs once at diff-fetch over the pull request's
**title**, **source branch name** and **description** — *not* commit messages, file contents, or
comments. Put the reference in the description unless a scenario says otherwise. A second level then
mines the *text of what level 1 retrieved* for new references (one hop only, `MAX_DEPTH=2`), each
provider capped at 10 references per level, the whole fan-out bounded to 20s.

**Real data only — for the context, not the harness.** Every object a scenario *resolves* (issue,
merge request, epic, ticket, page) must already exist for a real reason: never invent one to make a
scenario pass, because a fabricated ticket teaches you nothing about whether real ones resolve. The
PR/MR doing the referencing is the harness, not the data — opening one in a dedicated test
repository is how the pass is run. On GitHub and GitLab, note that a bare `#N` also resolves **pull
/ merge requests**, so an existing PR number is a valid bare reference in a repository that has no
issues.

### Part 1 — resolution without a PR (Test)

Fast, deterministic, and isolates grammar + credentials from the pipeline. Settings → Context →
**Test** on each row.

| # | Provider | Input | Expected |
|---|---|---|---|
| I-1 | jira | `PROJ-123` (a real key) | One item, kind `TICKET`, real summary/description |
| I-2 | confluence | a real page URL (`…/pages/12345/…`) | One item, real page title and body text |
| I-3 | github-issues | `owner/repo#123` (real) | One item, kind `ISSUE` (or `PULL_REQUEST` for a PR number) |
| I-4 | gitlab-issues | `group/project#123` (real) | One item, kind `ISSUE` |
| I-5 | github-issues | bare `#123` | **No result** — the guidance message ("Enter owner/repo#123 or paste an issue URL"). Test has no PR behind it, so a repository-relative reference has nothing to resolve against. This is expected, not a failure |
| I-6 | gitlab-issues | bare `#123` / `!123` / `&123` | Same: guidance message, no result |

A wrong result here (an item for a reference you did not mean) is more serious than an empty one —
stop and report it.

### Part 2 — resolution inside a real review, per SCM

For each scenario: enable **only** the provider named, put the reference in the PR/MR description,
trigger a review (push a commit, or use **Register PR** from the dashboard — Modes C/D — if you have
no tunnel running), then check the blob row.

**Platform-independent — run on each of GitHub, GitLab and Bitbucket:**

| # | Provider enabled | Put in the PR/MR description | Expected |
|---|---|---|---|
| I-7 | jira | a real `PROJ-123` key | CONTRIBUTED. Jira keys are site-global, so all three SCMs behave identically |
| I-8 | confluence | a real page URL on the configured host | CONTRIBUTED |
| I-9 | jira | a key whose ticket description itself links a Confluence page, **with both jira and confluence enabled** | CONTRIBUTED, and the blob is visibly larger than I-7's — this is the level-2 hop. Skip if no such real ticket exists |

**GitHub PR:**

| # | Provider enabled | In the description | Expected |
|---|---|---|---|
| I-10 | github-issues | bare `#123` (a real issue in *this* repo) | CONTRIBUTED — repo-relative resolves because the review is on GitHub |
| I-11 | github-issues | `otherowner/otherrepo#123` | CONTRIBUTED — a qualified reference names its own repository |
| I-12 | github-issues | a full issue URL, and a full `/pull/N` URL | CONTRIBUTED, two items; the PR one is kind `PULL_REQUEST` |

**GitLab MR:**

| # | Provider enabled | In the description | Expected |
|---|---|---|---|
| I-13 | gitlab-issues | bare `#123` (real issue in *this* project) | CONTRIBUTED |
| I-14 | gitlab-issues | bare `!123` (a real MR in this project) | CONTRIBUTED, kind `PULL_REQUEST` |
| I-15 | gitlab-issues | bare `&7` (a real epic) | Premium tier: CONTRIBUTED, kind `EPIC`. **Free tier: no epic item, and that is correct** — 403/404 skips that one reference, so pair it with a `#123` in the same description and confirm the issue still contributes |
| I-16 | gitlab-issues | `group/subgroup/project#123` | CONTRIBUTED — nested namespaces, the multi-segment qualified form |

**Bitbucket PR:**

| # | Provider enabled | In the description | Expected |
|---|---|---|---|
| I-17 | jira + confluence | a real key and a real page URL | CONTRIBUTED. There is no Bitbucket issue provider — Jira/Confluence *is* the Bitbucket context path, and it is the one this project was built around |
| I-18 | github-issues | `owner/repo#123` (a real GitHub issue) | CONTRIBUTED — a qualified reference is not platform-gated, so a Bitbucket PR can pull GitHub issue context |

I-18 paired with the negative pass below is the important one: it proves the cross-platform guard is
per-*reference*, not per-provider.

### Part 3 — negative pass: the cross-platform `ScmType` guard

The hazard: the same `workspace/slug` routinely exists on two platforms, so a bare `#123` resolved
against the wrong host returns a **real but unrelated issue** — a plausible wrong answer, not an
error. The guard declines rather than guesses.

**Run on a GitLab MR or a Bitbucket PR**, with **only `github-issues` enabled**, and a description
containing a bare `#123` that exists as a real issue in a same-named repository on GitHub (your own
`artyomsv/*` pair works).

Expected: **NOT CONTRIBUTED** — no blob row. The review's own platform fails the bare reference's
check before GitHub is ever queried. A GitHub issue appearing here is the cross-wiring defect the
guard exists to prevent — stop and report it.

Note the asymmetry against I-18, same SCM, same provider: the *qualified* reference contributes and
the *bare* one does not. That pair is the whole design, observable in two runs.

This is a **live-review-only** check. A provider's **Test** button short-circuits a bare reference to
the guidance message (I-5/I-6) before any provider is constructed, so there is no resolution attempt
for the guard to agree or disagree with. Only a real review on a real PR/MR of the other platform
exercises it.

### Part 4 — the last hop: does the item reach the model?

**Nothing in Parts 1–3 establishes this.** Assembly persists a blob; the prompt is built from it as
a separate step (`ReviewWorker.loadContext` → `ReviewPromptBuilder.build`), and no screen shows the
rendered prompt for a real review — it is never logged or persisted anywhere (see
`techdebt/spire-review-worker/2-2-no-visibility-into-rendered-llm-prompt.md`; `review_llm_call`
stores only model, token counts and cost). Do not go looking for a "prompt" view; there isn't one,
by design — the raw request can carry retrieved source text, and logging it in plaintext was
rejected as a bigger exposure than not having the view.

That hop is covered instead by a permanent CI test, which captures the exact `Prompt` handed to the
LLM client and asserts a context item's title and body are inside it:

```bash
./gradlew :spire-review-worker:test --tests '*ReviewWorkerTest*'
```

`assembledContextReachesThePromptSentToTheModel` is the one that matters. It is confirmed to
discriminate (it fails when `contextRef` is null).

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| No `ContextAssembled` at all; stage never leaves Received | Review-mode is `observe` |
| `ContextAssembled` but never a blob row, every scenario | Provider disabled, or its allow-list (project-keys field) excludes the repository — an **empty** allow-list accepts everything; a wrong entry silently excludes |
| Only the bare-reference scenarios fail | Expected off-platform (that is Part 3); on-platform it means the review's SCM provider type did not resolve — check Settings → Providers |
| Contribution missing after ~20s, worker logs "did not contribute within the budget" | Fan-out timeout — a slow host, or too many references |
| Test works, review does not | Extraction reads only title / branch / description; check the reference is in one of those |

### Known limitation of this pass

`ContextRequested` and `ContextContributed` reach the orchestrator dashboard with an **empty
reviewId and empty detail** (`ResultSaga.reviewIdOf` has no case for either, and neither is appended
to the per-review projection). So per-source, per-review attribution does not exist at runtime —
which is why every scenario above enables one provider at a time and reads the blob row. Worth
closing before the next context provider lands; it is a two-line addition to `reviewIdOf` plus a
`projection.appendEvent` on `ContextContributed`.

### Cleanup

Remove any provider added only for this pass in Settings → Context (or
`DELETE /api/context-providers/{id}`). Context blobs vanish with their reviews — no separate cleanup.
Flip Review-mode back to `observe`.

---

## Mode J — operator authentication (D10)

Dev boots with authentication **off**, so every other mode in this document runs unchanged. This mode
turns it on and checks the boundary from the outside.

### Start an identity provider

**Option A — the bundled one.** Imports the realm on first boot, nothing else to do:

```bash
docker compose -f docker-compose.yml -f docker-compose.idp.yml up -d keycloak
```

**Option B — one you already run.** Import the realm once, either through the admin console
(Realms → Create realm → browse to the file) or over the admin API:

```bash
curl -s -X POST http://<your-keycloak>/admin/realms \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data-binary @infra/keycloak/realm-spire.json
```

Either way the realm defines three clients (one per service), both roles, the audience mappers the
services require, and two obviously-synthetic users.

#### Dev logins

Defined in `infra/keycloak/realm-spire.json`, so they exist in any Keycloak the realm is imported into.
**Development fixtures only** — they are committed to this repository in plain text, which is exactly
why they must never be created anywhere reachable from outside a workstation. Each password is the
username.

| User | Password | Roles | Can do |
|---|---|---|---|
| `dev-operator` | `dev-operator` | `spire-admin` + `spire-viewer` | everything — register a PR, re-run, delete, replay the DLQ, and every settings screen |
| `dev-viewer` | `dev-viewer` | `spire-viewer` | read reviews only — the reviews list, a review's detail/timeline/threads/context, and the attention panel. **No Configure section at all** (General, Context, Repositories, Webhooks, LLM, Prompts, Dead-letter), no Register PR, no review-mode toggle, no re-run or delete |

The viewer's limits are enforced by the API (`@RolesAllowed`), not by the interface: every
configuration endpoint answers **403** for `spire-viewer`, reads included. The dashboard hides what it
cannot use as a courtesy, and grants nothing until it knows the role — so nothing administrative
appears even momentarily for a viewer.

Both clients allow the direct-access grant, so a shell can hold either identity without a browser:

```bash
curl -s -X POST http://localhost:34567/realms/spire/protocol/openid-connect/token \
  -d grant_type=password -d client_id=spire-orchestrator \
  -d client_secret=dev-only-orchestrator-secret \
  -d scope=openid -d username=dev-viewer -d password=dev-viewer
```

The per-service client secrets are `dev-only-orchestrator-secret`, `dev-only-gateway-secret` and
`dev-only-worker-secret` — likewise fixtures from the same file, and likewise never a deployment
credential.

#### The issuer has to be one value, and that is what picks the URL

The browser and the service both talk to the identity provider, and Keycloak answers with whatever
hostname it was *reached by* unless pinned. Get this wrong and login fails at the last step, with a
token whose `iss` does not match what the service discovered — not at startup, where it would be
obvious.

- **Bundled (Option A):** already solved, and worth knowing how. `KC_HOSTNAME` pins the
  *frontchannel* to `http://localhost:34567` while `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` lets the
  backchannel follow the request, so discovery fetched from inside the compose network returns a
  browser-reachable `authorization_endpoint` (`localhost:34567`), a container-reachable
  `token_endpoint`/`jwks_uri` (`keycloak:8080`), and **one** `issuer` both sides agree on. A
  containerized service therefore points at `http://keycloak:8080/realms/spire`; a host-run one at
  `http://localhost:34567/realms/spire`.
- **Your own (Option B)**, which typically has no `KC_HOSTNAME` pin: `localhost:<port>` from the
  browser and `host.docker.internal:<port>` from a container are **two different issuers**, and every
  login fails. Pick one name that resolves from both sides and use it everywhere —
  `host.docker.internal` does on Docker Desktop, including from the Windows host, so
  `SPIRE_OIDC_AUTH_SERVER_URL=http://host.docker.internal:<port>/realms/spire`. (A host-run stack
  reaching a host-published Keycloak has no such split; `localhost` is already common to both.)

Redirect URIs are unaffected by this choice — they name *the app's* origin, not the provider's.

### Run with authentication on

**The containerized stack** (`docker-compose.dev.yml`) — add the `docker-compose.auth.yml` overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml \
               -f docker-compose.idp.yml -f docker-compose.auth.yml up -d --build
```

`--build` is not optional. The dev images bake the source in and it is `--watch` that streams later
edits; a plain `up -d` restarts the *existing image*, so a stack left running across a branch serves
old code while looking perfectly healthy. The symptom here is authentication apparently refusing to
switch on — because the endpoints the flags govern are not in the image yet. Drop the one `-f` and
re-run to go back to an unauthenticated stack.

**A host-run service** — the same three switches as `-D` flags:

```bash
export SPIRE_OIDC_AUTH_SERVER_URL=http://localhost:34567/realms/spire
export SPIRE_OIDC_CLIENT_SECRET=<from the realm>
./gradlew :spire-orchestrator:quarkusDev \
  -Dquarkus.oidc.enabled=true \
  -Dspire.security.auth-enabled=true \
  -Dquarkus.http.auth.permission.operator.policy=authenticated
```

All three are needed together: `oidc.enabled` decides whether there is OIDC at all, the permission
policy decides whether an identity is *required*, and `auth-enabled` governs the role checks that run
afterwards. A subset leaves the service half-authenticated — the API refusing while a socket still
opens, or every operator denied because no roles were read. (`oidc.enabled` is build-time config, but
an environment variable still overrides the `%dev` default, which is how the compose overlay sets it
without restating each service's gradle command.)

### What to check

Ports below are the host-run stack (orchestrator `34080`, gateway `34081`, dashboard `34000`). On the
containerized stack read them as `39280` / `39281` / `39285`.

| # | Check | Expected |
|---|---|---|
| 0 | `curl -i :34081/webhooks/github/unknown-key` through the Cloudflare tunnel, if one is running | **404** — not 502. Recreating the gateway changes its container IP; this proves cloudflared re-resolved it, and that turning auth on left `/webhooks/*` public |
| 1 | `curl -i :34080/api/reviews` | **302** to the identity provider |
| 2 | `curl -i -H 'X-Requested-With: JavaScript' :34080/api/reviews` | **499** + `WWW-Authenticate: OIDC` — the status the dashboard acts on |
| 3 | `curl -i -H 'Authorization: Bearer bogus' :34080/api/reviews` | **401** — bearer is challenged as bearer, so scripted access still works |
| 4 | `curl -i :34080/api/me` | **200**, `authenticated:false` — readable without a session by design |
| 5 | `curl -i :34080/q/health` | **200** |
| 6 | `curl -i -X POST :34081/webhooks/github/unknown-key` | **404**, never 401 — an SCM has no token to present |
| 7 | Open the dashboard | redirected to the provider; sign in as `dev-operator` and land back on the dashboard |
| 8 | Sign in as `dev-viewer` instead | the rail shows **Reviews only** — the whole Configure section is gone, along with the Register PR button and the review-mode toggle; no re-run or delete on a review |
| 8b | As `dev-viewer`, navigate straight to `#/settings/llm` | bounced to the reviews list. The nav is a courtesy; a bookmark reaches a route without it |
| 9 | As `dev-viewer`, `curl` the DLQ and any registry (`/api/providers`, `/api/llm-providers`, `/api/prompts`, `/api/settings/review-mode`) with that session | **403** on every one — configuration is admin-only including its reads |
| 10 | Wait past the session lifetime (~5 min) with the dashboard open | it goes to a login, and does **not** sit reconnecting or claim the webhook gateway is down |
| 11 | Sign out | returned to the provider's login, and signing in again asks for credentials rather than silently resuming |
| 11b | Sign in, then open **Webhooks** and any review's **Context** card | both load. They are served by the gateway and the worker, which are separate sessions — signing in mints `/api` only, so the dashboard establishes the siblings via `<prefix>/auth/login`. If either says "failed to fetch", that step did not happen |
| 11c | With the dashboard open, check the attention bell reports no gateway outage | a missing `/gw` session used to present as "the webhook gateway is not responding", retried every 1.5s — a false BLOCKING row about a service that was fine |
| 12 | Compare the `302` from `/api/…` with the one from `/gw/…`, both via the dashboard's origin | **different `client_id`s** (`spire-orchestrator` vs `spire-gateway`) and **different callback paths** — the per-service isolation, visible without reading any config |
| 13 | Any `redirect_uri` in those redirects | the origin **you are browsing** (`:34000`/`:39285`), never a backend port — the dev-server proxy deliberately omits `changeOrigin`, which would rewrite `Host` and send the callback to the wrong origin |
| 14 | On an unpinned provider (option B), mint a token from **each** hostname it answers on and call the API with both | the one matching `SPIRE_OIDC_AUTH_SERVER_URL` gives **200**, the other **401**. Same server, same realm, same user — the issuer is part of the token, so this is what "both sides must use one hostname" actually means, and it is worth seeing once rather than trusting |

Check 10 is the one worth being patient for: it is the failure the socket lifecycle work exists to
prevent, and the only way to see it is to let a session actually lapse.

Checks 1–6, 9, 12 and 13 need no browser: the realm's clients allow the direct-access grant, so a
token for either synthetic user comes back from `/protocol/openid-connect/token` with
`grant_type=password`, and `Authorization: Bearer <it>` exercises the role split (`/api/dlq` **403**
as `dev-viewer`, **200** as `dev-operator`) straight from the shell.

---

## Mode K — the LLM cost ledger (ADR-023)

Config-time guards in Settings → LLM, then one real review to confirm the ledger it feeds. Sign in as
`dev-admin` (or run with auth off) — every endpoint here is admin-only.

### Setup

A catalogued model and a provider pointed at it, from an earlier mode. If not, catalog one in
Settings → LLM → Models with pricing mode `METERED` and real `INPUT`/`OUTPUT` rates, then a provider
in Settings → LLM → Providers naming it.

### K-1 — a `METERED` model produces per-type charge lines on a real review

Run any review that reaches `ReviewGenerated` (Mode A/B/C/D/E/F/G all work — this mode does not care
which SCM). Open the review detail's "Model usage" card.

**Expected:** one block per call (review, and reconcile on a second round), each with a line per token
type actually used — tokens, the rate that priced it, and the line's cost — and a dollar headline, not
a bare token count. If the model has no cache traffic, expect exactly `INPUT` and `OUTPUT` lines; a
`CACHED_INPUT`/`CACHE_WRITE`/`REASONING` line only appears when that dimension was actually non-zero,
matching `TokenUsageMapper`'s rule that a call without caching carries no zero rows.

### K-2 — an `UNMETERED` model reads as self-hosted, not `$0.00`

Catalog a second model, check "Self-hosted — no per-token cost (UNMETERED)" (the rate fields
disappear entirely once checked — there is nothing to fill in), point a provider at it, run a review.

**Expected:** the "Model usage" card's per-call headline reads **"self-hosted (unmetered)"**, never
`$0.00`. Those two read identically to an operator unless the mode is surfaced explicitly, which is the entire reason
`pricing_mode` exists rather than a plain number — `$0.00` is indistinguishable between "genuinely
free" and "nobody told us the price."

### K-3 — a `METERED` model with a blank rate is refused, not saved as free

In Settings → LLM → Models, create or edit a model with pricing mode `METERED` and leave the Output
rate field blank, then Save.

**Expected:** the form refuses before any request leaves the browser — *"Output rate is required for
a metered model."* Now enter `0` instead of leaving it blank, and Save again: a **different**
message, *"Output rate must be greater than zero."* — the client validator branches on the two cases
separately (blank vs. non-positive) rather than treating them as one. Confirm the same pair of
messages fires for Input alone.

**The distinction is the point, not an implementation detail worth glossing over.** Blank means "the
operator hasn't decided yet" — a form left incomplete. An entered `0` means the operator typed a
value and believes the model is free — the exact case `UNMETERED` exists for, not a validation
nag. A runbook (or a form) that collapses these into one message teaches the wrong mental model of
the rule ADR-023 added: zero is a category (`UNMETERED`), not a number that failed a range check.

Bypass the client (a direct `POST`/`PUT` to `/api/llm-models` with an `OUTPUT` rate of `0` or omitted)
to see the **server's own guard**, which exists precisely because the UI is a courtesy, not the
control. Both cases — blank and `0` — collapse to the **same** server-side message, unlike the
client: **400**, *"A METERED model needs a rate above zero for OUTPUT. If this model is self-hosted
and costs nothing to call, set its pricing mode to UNMETERED instead of entering a zero — a zero rate
and an unentered rate must stay distinguishable."* The server does not need to explain the operator's
two different mistakes separately; it only needs to refuse both and point at `UNMETERED`.

K-4 and K-5 each need their **own** in-use model/provider pair, not K-1's and not each other's — K-4
ends by making its pair no-longer-in-use (that is the point of the scenario), so a pair K-5 also
depended on would already be gone by the time you reach it. Catalog a fresh throwaway model for each
(e.g. `TEST-K4-MODEL`, `TEST-K5-MODEL`) with a provider naming it, before starting that scenario — do
not reuse a pair from an earlier scenario in this mode.

### K-4 — deleting a model a provider uses is refused

With `TEST-K4-MODEL` catalogued and a provider naming it, attempt to delete the model
(Settings → LLM → Models → delete).

**Expected:** **409**, *"Model 'X' is in use by N LLM provider(s). Point them at another model
first."* The model remains in the catalog. Repoint the provider at a different model and retry —
deletion should now succeed. This scenario is now finished; its pair is gone by design, and neither
half of it should be reused by K-5 or anything after it.

### K-5 — renaming a model a provider uses is refused

With `TEST-K5-MODEL` catalogued and a **separate** provider naming it — untouched by K-4 — attempt
to rename the model (edit its `name` field and save; leave every other field alone).

**Expected:** **409**, *"Model 'X' is in use by N LLM provider(s). Point them at another model
first, then rename it."* The rename does not take effect — check the model's name in the list, or
the provider's `model` field, rather than trusting the toast alone. Renaming the model's label,
rates or any other field with the *same* name should still succeed; only the name itself is
guarded. Repoint or delete the provider afterward if you want to delete `TEST-K5-MODEL` too — it is
still in use at the end of this scenario, deliberately, since the rename was refused rather than
applied.

### K-6 — a provider naming an uncatalogued model is refused

Create or edit a provider (Settings → LLM → Providers) naming a model not in the catalog. The Model
field is a dropdown built from the catalog **only when one exists for that provider type** — pick a
provider type with zero catalogued models first (or use `POST /api/llm-providers` directly) to reach
the free-text fallback, then type a name you have not catalogued.

**Expected:** **400**, *"Model 'X' is not in the catalog with usable pricing. Add it under Settings ->
LLM -> Models first, with a rate for input and output tokens — or mark it UNMETERED if it is
self-hosted and costs nothing."* The provider is not saved. This is the same rule K-3/K-4 protect from
the other direction — a provider can never reference a model the catalog cannot price. Once at least
one model of that type is catalogued, the dropdown replaces the free-text field and this path is no
longer reachable from the UI at all — *"the Settings dropdown is a courtesy; this is the control,"*
in the words of `LlmProviderModelGuardTest`'s own javadoc, the test that exercises this exact path.

### Cleanup

Delete any model/provider pair added only for this pass. For a pair still in use (K-5's, and K-4's
before you repointed it), repoint the provider at a different model — or delete the provider
outright — before deleting the model itself; deleting an in-use model is refused by the same guard
K-4 exercises.

---

## Mode L — review archival and retirement (ADR-024)

Archiving replaced the hard delete: nothing is destroyed, and the pull request is **retired** so no
further reviews run for it. This mode walks one review through archive → retirement → unarchive on a
real PR. Sign in as `dev-admin` (or run with auth off) — archive, unarchive and re-run are admin-only.

### Setup

One **completed** review on a real PR from any earlier mode (B/C/D/E/F/G all work — this mode does not
care which SCM), with the webhook still live so replies and `/review` reach the gateway. You will be
commenting on that PR, so pick one you are happy to leave a few bot comments on. Note its cost and
model from the review detail's "Model usage" card before you start — L-1 checks they survive.

### L-1 — archiving destroys nothing and takes the review off the live list

On the review detail page, press the **Archive review** button (the box icon; it sits where Delete used
to). Read the confirmation copy, then confirm.

**Expected:** 204, the row disappears from the reviews list, and the detail page keeps working — same
`status` (`completed`, **not** overwritten by archival), same PR-state badge, same findings, and the
**same cost, model and charge lines** as you noted in Setup. That last one is the whole point: the old
delete took the charge ledger with it, so a review removed for being clutter destroyed real paid usage.

Now tick **Show archived** above the reviews list.

**Expected:** the row is back, faded, carrying an **Archived** tag with the archive timestamp on hover.
Untick it and the row leaves again. The existing filter chips operate on whichever set the checkbox
selects — Show archived is a different *request*, not another chip.

Press **Archive review** again from a second browser tab (or `POST …/archive` directly).

**Expected:** **409**, *"This review is already archived."* Archiving something that does not exist is
**404**, and archiving a review that is **still running** is a third answer — **409**, *"This review is
still running. Wait for it to finish, or cancel it, then archive."* Three distinguishable outcomes, not
one boolean failure: an in-flight worker's result would otherwise write status, findings and charges to
a row archival promises is frozen.

### L-2 — a reply gets exactly one notice, ever

Post a normal (non-command) reply on the PR as an **allowlisted** author — either in an existing
finding thread or as a plain PR comment.

**Expected:** the bot replies once with the fixed text *"This review has been archived, so no further
reviews will run for this pull request."* — in the thread if you replied in one, otherwise as a
top-level PR comment. The review's timeline shows an `ArchivedReviewSkipped` entry naming the event,
then an `ArchivedNotified` entry (*"told the pull request this review is archived"*). No model was
called, so the cost card is unchanged — confirm that, since it is the cheapest way to catch the notice
accidentally acquiring an LLM credential.

Reply **again**, and then reply once more in a **different** thread.

**Expected:** **no second notice, either time.** The claim is taken on a constant slot rather than on
the thread ref, which is what makes it once per *review* rather than once per thread — the opposite of
the turn-cap notice, deliberately. The worker logs `Archived notice already posted for … — staying
quiet` at INFO, and the orchestrator still records an `ArchivedReviewSkipped` timeline entry for each
event, so the silence is visible somewhere rather than being indistinguishable from a lost webhook.

Reply once as an author **not** in the provider's allowlist.

**Expected:** nothing at all — no notice, no timeline notice entry beyond the skip, and the log says
the author is not in the allowlist. A notice that answered any commenter would partly reverse the gate
that stops unlisted authors making the bot act.

### L-3 — the retired PR starts no new work

Comment `/review` on the PR.

**Expected:** **no review starts.** Status stays `completed`, the commit does not move, the pipeline
does not restart, and no new charge lines appear. The timeline records the skip. (If the notice has
not yet been spent — i.e. you skipped L-2 — this is one of the three events that spends it.)

Push a new commit to the PR branch.

**Expected:** same again — the update event is gated, no review runs, and the cost card is unchanged.
This is the reason retirement exists: an author pushing a commit must not silently re-bill an operator
who archived the review to be done with it.

Now try the two paths that never reach the saga. Press **Re-run** — it should not be offered while the
review is archived, so call it directly: `POST /api/reviews/{ws}/{slug}/{pr}/rerun`.

**Expected:** **409**, *"This review is archived. Unarchive it before re-running."* Then check that the
notice claim survived: reply on the PR again and confirm **still no second notice**. The re-run's first
act is to clear every worker claim for the review, so an ungated re-run would both resurrect the review
and silently re-arm a notice meant to fire once.

Finally, register the same PR from **Register PR** in the dashboard.

**Expected:** **409**, *"This pull request's review is archived. Unarchive it to review again."* — not
a 200 with a reviewId and nothing happening, which is how this path used to behave.

### L-4 — closing the PR does not move the badge

Close (or merge) the pull request at the SCM.

**Expected:** the PR-state badge **does not move** — it stays exactly as it was at archival, because an
archived review is frozen on all three dimensions. And **no notice is posted**: the close is gated like
the other three events but deliberately does not spend the once-ever notice, since a close is not a
human asking a question and spending it there would leave whoever later asks a real question with
silence. Only the timeline skip entry appears.

Reopen the PR if you want to continue; it changes nothing while the review is archived.

### L-5 — unarchive restores the review, and a later archive notifies again

Press **Unarchive review** (the restore icon, offered only while archived — the Archive and Unarchive
buttons are never both present) and confirm.

**Expected:** 204, the review returns to the live list **without** Show archived ticked, the Archived
tag is gone, and its cost and model are still the same figures from Setup. Now comment `/review`.

**Expected:** a review actually runs this time — the PR is live again, and a new charge line appears.

Let that review finish, then archive it again and post a reply on the PR.

**Expected:** a **fresh notice**, because unarchive released the notice's idempotency claim. Without
that release the second archival would be silent, which is the same "bot just stopped replying" symptom
the turn-cap notice exists to prevent.

### Cleanup

Unarchive anything you archived only for this pass, or leave it archived — nothing was destroyed
either way. Delete the bot's notice comments from the PR if you are reusing it for another mode; the
claim is keyed in the worker's `comment_idempotency`, not to the comment existing, so deleting the
comment does **not** re-arm the notice. Unarchive and re-archive if you need it armed again.

## Mode M — spend caps and the refused review (ADR-025)

Three gates refuse before a paid call: diff size on `DiffFetched`, the spend/call cap before the review
call, and the same cap on the conversation path. This mode drives the call cap, because it is the axis
that fires on any deployment — including an `UNMETERED` one, where the money cap cannot fire at all by
design. Sign in as `dev-admin` (or run with auth off): the limits are admin-only, as every registry is.

### Setup

A working review loop on a real PR from any earlier mode (B/C/D/E/F/G all work — this mode does not
care which SCM), with the webhook live so `/review` reaches the gateway.

Open **Settings → General**, find the **Limits** group, and set **Window (minutes)** to `60`. Leave the
four limits above it blank — blank is unlimited, and that is the shipped default. Then open the
attention bell and confirm there is **no `CAP_REACHED` row**, which is how you know the window is
currently empty. If a review has already run in the last hour its calls are already counted, so either
wait for the hour to roll or add that number to the cap you set in M-1; the cap counts *calls in the
window*, not calls since you set it.

### M-1 — the first review runs, the second is refused

Set **Call cap** to `1` and save. Comment `/review` on the PR and let it finish.

**Expected:** it reviews normally — the cap counts usage *already in the window*, and at the moment the
gate ran there was none. Its review call is now the one call in the window.

Comment `/review` again.

**Expected:** **no review runs.** The reviews list shows the review as **Refused** (an amber pill, not
the red Attention pill a failure gets, and — the regression to watch for — not a green "done" with a
full progress bar). It appears under the **Needs attention** chip and is counted both there and in the
"Needs attention" summary tile: a refusal always leaves the operator a decision, and the diff gate in
M-5 raises no attention row at all, so this chip is the only place that kind of refusal can be found.
Open the detail page:

- The **note** is actionable and names both figures: *"Not reviewed: call cap reached — 1 of 1 calls
  used. Capacity returns as older usage ages out, or raise the cap in Settings -> General."*
- The **timeline** carries a `refused:GenerateReview` entry with the same sentence.
- There is **no error** on the page. A cap refusal is a policy decision, so `setError` is deliberately
  never called — an infrastructure error here would send the operator looking for an outage.
- The **cost card is unchanged**: nothing was spent, which is the point.
- **Nothing is posted to the pull request.** No comment, no reply. Replying would confirm to anyone
  probing that the command is wired, and would cost an API call per probe.

Wait past the stuck-review threshold and re-check the attention bell.

**Expected:** **no `REVIEW_STUCK` row for this review.** `refused` is terminal, so the stuck query
excludes it. Before this status existed the review sat in `reviewing` and eventually produced a row
blaming *"a webhook delivery path or a worker"* — a lie about a deliberate decision.

### M-2 — a refused review can be cleared

On the refused review's detail page, press **Archive review** and confirm.

**Expected:** 204, and the row leaves the live list exactly as in Mode L. This is the half that used to
be impossible: the archive guard refuses any row still `reviewing`, so a refusal that never reached a
terminal status could not be archived at all. Tick **Show archived** to confirm the row is intact.

Unarchive it again so the rest of this mode has a live review to work with.

### M-3 — the attention row appears, names when capacity returns, and clears itself

Open the attention bell while still over the cap.

**Expected:** a `CAP_REACHED` row reading *"Call cap reached — 1 of 1 calls used. Capacity returns at
2026-08-10T01:23:45Z."* — a whole-second UTC instant, not a relative phrase — linking to
Settings → General. Check that instant against the review you ran in M-1: it is that call's
`llm_charge.priced_at` plus the 60-minute window, because that charge is the next one to age out. A
fixed bucket could only ever have said "at the top of the hour".

It sits with the **blocking** rows, beside things like a missing default LLM provider — not below them
as a warning. A cap doing what it was told is not a fault, but severity here describes impact: while it
holds, nothing will run. Confirm it is not filed as a lesser row.

There is **no dismiss control**, unlike the two cost rows. Confirm it clears both ways:

- **Raise the limit.** Set Call cap to a larger number, save, reopen the bell — the row is gone
  with no acknowledgement anywhere.
- **Or let it age out.** Set the limit back to `1` and wait for the window to roll past the M-1 call.
  The row disappears on its own.

Either way the row went away because the *condition* went away, which is the attention panel's whole
contract — nothing is stored and nothing is dismissed.

### M-4 — an @-mention does not buy a way around the cap

Set Call cap back to `1` while a call is still in the window, so the cap is tripped. On the PR,
reply in a bot-created finding thread and **@-mention the bot** explicitly.

**Expected:** **no answer.** The review's timeline records `refused:AnswerFollowUp`, and the orchestrator
logs the refusal. This is the case the whole feature exists for: threads cost nothing to open, the turn
cap is per *thread*, and an @-mention removes that turn cap by design — so the spend cap is the only
thing making the conversation path finite. The gate sits deliberately *after* the mention override.

Now confirm the refusal did not rewrite the conversation's own review:

**Expected:** the review's **status and note are unchanged** — still `completed` (or whatever it was),
not `refused`. Declining to answer one reply is not a retraction of a review that already ran, and the
note would otherwise open *"Not reviewed"* on a PR that visibly was.

### M-5 — a giant diff is refused before any context is gathered

Clear Call cap (blank = unlimited) and set **Max changed files** to `1`. Comment `/review` on a PR
touching more than one file.

**Expected:** refused at the **diff** stage — the note reads *"Not reviewed: diff too large to review
(N files / M bytes). Raise the diff limit in Settings -> General, or split the pull request."*, and the
timeline entry is `refused:FetchDiff`, not `refused:GenerateReview`. Confirm the **Context card shows
nothing was assembled**: this gate sits before `GatherContext` precisely so a diff nobody will review
does not first spend per-issue API calls, a bounded 20-second wait and an encrypted blob write.

### M-6 — clearing every limit restores today's behaviour exactly

Blank all four limits in the Limits group and save. Reload the page.

**Expected:** all four come back **blank**, not `0`, each showing its `unlimited` placeholder. A blank
field that round-tripped as `0` would turn "no cap" into "a cap of zero" and refuse every review — the
same shape as the unknown-became-zero defect ADR-023 exists to prevent. Try entering `0` in any of
them: the form refuses before the request leaves the browser — *"Call cap must be a positive whole
number, or left blank for unlimited."* (**Window (minutes)** is the one field that does not come back
blank — it has an effective default, so a blank window reloads as `1440`. A rolling window with no
length is not a weaker cap, it is a meaningless one.)

Comment `/review` on the PR that was refused in M-5.

**Expected:** a review runs normally and posts as usual. Unset means unlimited, so a deployment that
configures nothing behaves exactly as it did before this feature existed — which is the property that
makes the upgrade safe.

### Cleanup

Leave the limits blank unless you actually want caps on this deployment. Archive or unarchive the
refused reviews as you prefer — nothing was spent on them, and nothing was destroyed.

## Mode N — conversation-derived findings (`/finding`) on every SCM

`/finding [severity] [message]` lets an allowed author file the thread they're in as a tracked
finding instead of leaving it as prose the reviewer never revisits — severity is one of `blocker`,
`major`, `minor`, `info`, `nit` (case-insensitive; a first word that isn't one of those is just the
start of the message, and severity defaults to `minor`). Run this once per SCM — GitHub, GitLab,
Bitbucket — on a PR from any earlier mode with a completed review.

### N-1 — filing off-line is refused, on-line is filed and confirmed

On the summary/PR-level thread (not an inline comment), reply `/finding major this leaks a handle`.

**Expected:** no finding is filed. The bot replies in that same thread: *"`/finding` needs to be on a
specific line. Open an inline comment on the line in question and run it there."* Reply `/finding`
again in the **same** thread.

**Expected:** the **same** refusal text, not a second copy — the claim is per thread, and a second
misuse there has not been helped by hearing it again.

Now open a **new inline comment** on an unflagged line (or reply inside an existing finding's thread)
and post `/finding major shadows the outer variable`.

**Expected:** the bot replies in that thread within a few seconds: *"Filed as **MAJOR** at
`<path>:<line>`. It will be tracked with the review's other findings and reconciled on the next
push."* — the path and line matching exactly where the comment was posted. Reply `/finding` again in
the **same** thread with different text.

**Expected:** a **second** confirmation, naming the same anchor — a second `/finding` in one
discussion is a second finding and gets its own confirmation, unlike the refusal and the turn-cap
notice, which are once-per-thread by design.

### N-2 — the finding appears in the Findings card, marked as from a discussion

Open the review detail page.

**Expected:** the Findings card shows the filed finding (severity **Major**, the message from N-1)
tagged **from discussion** — distinct from every review-discovered finding on the same card, which
carries no such tag. It also counts toward the card's open-finding total exactly like a
review-discovered finding does (re-run N-1 with `/finding blocker …` instead if you want to confirm it
moves the **blocker** count too).

Findings are Tink-encrypted at rest (`review_status.open_findings_json` — may quote source/comment
text, DATA-MODEL §5), so unlike Mode G's thread rows there is no plaintext column to read the content
back from directly; the detail page (which decrypts server-side) is the check. To confirm the write
actually landed rather than silently no-op'd, it is enough that the column moved:

```sql
SELECT updated_at, open_findings_json IS NOT NULL AS has_findings
  FROM orchestrator.review_status WHERE review_id = 'review::<ws>/<slug>#<pr>';
```

### N-3 — a redelivered `/finding` does not file twice

Using the SCM's own UI, edit the N-1 comment that triggered the confirmation (or otherwise force a
redelivery of the same webhook event, if your provider's delivery log offers a resend). If neither is
practical, skip this step — it is the redelivery guard `raisedFindingComments` exists to cover, not a
distinct user-facing behaviour.

**Expected:** no second finding, no second confirmation. The idempotency key is the triggering
comment's id, the same shape as `/review`'s.

### N-4 — the finding reconciles like any other on the next push

Push a commit that genuinely fixes the N-1 finding (change the shadowing variable's name), on the same
branch.

**Expected:** the next round's summary reflects it — the thread gets a closing reply and is marked
**resolved** on the SCM, and the review detail page shows the finding as **RESOLVED**, not
`STILL_OPEN` or silently dropped. It went through the same reconcile call and the same
`PriorFinding`-carried snapshot as a review-discovered finding, tagged `origin: conversation` the whole
way — there is no separate code path for a conversation finding's reconciliation to fall out of.

**Verify from the read model:**

```sql
SELECT type, thread_ref, detail FROM orchestrator.review_event
 WHERE review_id = 'review::<ws>/<slug>#<pr>' AND type = 'ThreadResolved'
 ORDER BY seq DESC LIMIT 3;
```

### Where providers legitimately differ

Same as Mode G: Bitbucket's inline comment is single-anchor, resolve mechanisms differ per provider,
and mention syntax is irrelevant here since `/finding` needs no `@mention` to engage — but all three
must end with a real reply posted in-thread and a real `resolved: true` in N-4.

### Cleanup

None needed — the filed finding is real review data once N-4 resolves it, and archiving the review (if
this was a throwaway PR) removes it from the live list without deleting it (ADR-024).

## Mode O — repository knowledge base: a resolved code snippet reaches the review (P3 rung 1)

Proves ADR-026's rung 1 end to end against a real repository: a diff that calls an imported symbol
resolves the symbol's own definition through the repository's import graph, the resulting
`CODE_SNIPPET` item is visible on the review, and the finding reflects it. This is the manual
counterpart to the seam test in `ReviewWorkerTest` — that test proves a snippet reaches the `Prompt`
object; this mode proves the whole pipeline that produces the snippet in the first place, against a
real host.

### Setup

1. Stack up, active mode, default LLM configured (same prerequisites as Mode I).
2. Register a `code` context provider in Settings → Context: type **Repository code**, baseUrl the
   API root of whichever platform hosts your test repository (`https://api.github.com` for GitHub —
   `…/api/v3` on Enterprise Server, `https://gitlab.com` for GitLab — no `/api/v4` suffix,
   `https://api.bitbucket.org/2.0` for Bitbucket Cloud), authKind `bearer`, a token that can read
   repository contents. Path allow-list left blank reads from anywhere in the repository; a prefix
   like `src/` narrows it (it's a prefix match — `src/foo` also matches `src/foobar/`, so add a
   trailing slash when a whole directory is meant). **The platform is inferred from this host** — a
   self-managed GitLab whose hostname does not contain "gitlab" is read as GitHub
   (`WorkerContextClients.readerFor`). **Live preview is not available for this type** — Settings
   shows that message in place of a Test box; use **Check** instead, which requests a
   near-certainly-absent path and reads 2xx/404 as proof the token was accepted (401/403 as
   rejection).
3. Pick or create a Java or TypeScript repository — rung 1 ships exactly these two languages via
   `LanguageSupport`, nothing else resolves. It needs at least two files where one imports and calls
   something from the other, e.g. a `Pricer` class with a `chargeFor` method, imported and called
   from a separate `Billing` file.

### Trigger

4. Open a PR/MR that changes only the **calling** file, touching the line that calls the imported
   symbol (add a second call, or change how the result is used) — the **definition** file must stay
   unchanged in this diff. This is the shape rung 1 targets: the identifier appears on a changed
   line, and the file that defines it doesn't need to change for the reviewer to need its definition.
5. Trigger a review (webhook, or Register PR — Modes C/D/E/F).

### Observe

6. **Context card**, review detail page: a row with kind `CODE_SNIPPET` and title
   `<symbol> — <path>` (e.g. `chargeFor — src/Pricer.java`), expandable to the extracted snippet —
   the definition's signature plus up to 40 body lines. The card only refetches once the pipeline's
   Context stage completes for this run, so check after the review has reached at least the Review
   stage, not immediately on registration.
7. **The posted finding** — if the diff changed how the definition is used incorrectly (wrong unit
   passed, a documented return value ignored), read the finding text and confirm it engages with what
   the snippet showed, not just what is visible in the diff alone. There is no separate UI tag for a
   code-context-influenced finding the way `/finding` gets `origin: conversation` — this is judged by
   content, not a marker.
8. **Attribution**, same caveat as Mode I: enable only the `code` provider so a `worker.context_blob`
   row for this review can only have come from it. Mode I's Postgres query
   (`SELECT review_id, size_bytes, created_at FROM worker.context_blob …`) works unchanged here if the
   Context card alone leaves you unsure whether anything was assembled.

### The last hop, specifically for code

Mode I's Part 4 already proves an assembled context item reaches the `Prompt` object sent to the
model in general. `{{code_context}}` is a separate prompt slot from `{{context}}`
(`ReviewPromptBuilder.renderContext`) with its own token budget, so that proof does not cover this
path by itself — a break in one slot's rendering can leave the other looking healthy. A sibling test
closes that gap:

```bash
./gradlew :spire-review-worker:test --tests '*ReviewWorkerTest*aCodeSnippet*'
```

`aCodeSnippetReachesThePromptSentToTheModel` asserts a `CODE_SNIPPET` item's body reaches the
`Prompt` actually sent to the model, and is confirmed to discriminate the same way its sibling is
(fails when `ReviewPromptBuilder` is made to render `code_context` empty).

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| No `CODE_SNIPPET` item; `worker.context_blob` row absent or small | The diff's language has no `LanguageSupport` (only Java/TypeScript ship in rung 1), or the touched identifier doesn't match anything the calling file's import graph resolves — check the import statement actually names the file you expect |
| Item present but from an unexpected file | A file reached through one import can surface a snippet for an identifier that import didn't itself bring in, if the file happens to declare something else of the same name (`CodeContextProvider` javadoc) — the item still names its own true definition path, so this is a known, narrow misattribution, not a defect to chase |
| Check fails with 401/403 | Credential rejected, same as every other provider type |
| Check passes but a review never resolves anything | The token's platform doesn't match the host heuristic — see the platform-inference note in Setup step 2; use a hostname containing the platform name |
| Path present in the repo but never fetched | Path allow-list excludes it — an **empty** allow-list accepts everything; a configured one that doesn't cover the definition file silently excludes it |

### Cleanup

Remove the provider added for this pass in Settings → Context (or `DELETE
/api/context-providers/{id}`). Context blobs vanish with their reviews — no separate cleanup. Flip
Review-mode back to `observe`.

---

## Mode P — an operator proves their own SCM account by signing in (ADR-028)

Proves the self-service half of the operator mapping end to end: an admin registers an OAuth
application, an operator clicks one button, the platform confirms who they are, and their own
activity screen fills in. Nothing here can be exercised by a test suite — the sign-in is a real
consent screen on a real platform — so this mode is where the flow is verified.

**What it replaces.** Before this, an admin typed an OIDC subject and a stable provider id, and the
resulting claim could not be checked by anything. This is the platform answering instead.

### Setup

1. Stack up with authentication ON (`docker-compose.idp.yml`, Mode J's prerequisites). The flow is
   meaningless with auth off: there is no operator identity to link to.

   **No tunnel, unlike a webhook.** The `--profile tunnel` service exists because the SCMs *push* to
   us and cannot reach a private compose network. A sign-in is the other direction on both legs: the
   platform redirects the operator's own browser to the callback, and this deployment calls *out* to
   the platform's token endpoint. Nothing has to reach you from the internet, and GitHub, GitLab and
   Bitbucket all accept a `localhost` callback URL.

   That tunnel is also the wrong URL to reuse if you tried: it forwards to `gateway:39281`, which
   serves `/webhooks/*` and has no callback route — the redirect would 404. If you genuinely need a
   public address (signing in from another device), tunnel the **UI** on `39285` instead, since that
   is the single origin every service answers behind, and set `SPIRE_PUBLIC_HOST` so the address
   this page shows matches the one you registered.
2. Register an OAuth application on the platform you are testing. **One per platform for the whole
   deployment, not one per person**, and it goes on the account that owns your repositories — not on
   a repository, and not on the bot account whose token the provider registry already holds. That
   credential proves the *reviewer's* identity; this one lets a person prove their own.

   **Match the application to whoever owns the repositories.** If they belong to an organization,
   group or workspace, put it there. If they are one person's own repositories, a personal
   application is the right answer rather than a compromise — the two share a fate anyway, so there
   is nothing for a shared account to outlive. The only costly mismatch is the other direction: a
   personal application in front of an organization's repositories leaves when that person does,
   and every operator's link goes with it.

   - **GitHub** — the organization (or your own account) → Settings → Developer settings →
     OAuth Apps → New OAuth App. No permission section to fill in: the profile-only scope is
     requested per sign-in.
   - **GitLab** — the top-level group → Settings → Applications, or your own account → Edit profile
     → Applications. Scope `read_user`, **Confidential** ticked (without it GitLab issues no client
     secret at all). A self-managed instance can also hold one instance-wide in the Admin area,
     which is the better choice when several groups are reviewed.
   - **Bitbucket** — the workspace → Workspace settings → OAuth consumers, permission
     **Account: Read**, and **This is a private consumer** ticked (a public consumer has no client
     secret). No decision to make here: every Bitbucket account has a workspace, a solo one named
     after you, and consumers exist only on a workspace.
3. In the dashboard, Settings → **Operators** → *Sign-in applications* → **Set up** for that
   platform. Copy the **redirect address** shown there into the application you just registered —
   it must match exactly, including scheme, host, port and path. Paste the client id and secret.
   Leave both base URLs blank for the hosted services; fill them for a self-managed install.

> **The client secret is yours to paste.** Nothing in this runbook asks anyone else to handle it,
> and the API never returns it once stored — a read reports only whether one is set.

### Steps

| # | Do | Expect |
|---|---|---|
| 1 | Sign in as a **viewer** and open **My activity** | The unlinked state, with a **Connect my \<platform\> account** button — not an empty chart |
| 2 | Click it | The platform's own consent screen, naming your application and asking only for profile access |
| 3 | Approve | Back on **My activity** with *Your SCM account is linked*, and the linked account shown as a chip |
| 4 | Compare the chip's id against a review that platform produced | Identical. This is the assertion that matters: a link to any other spelling of your identity matches no rows and looks exactly like having done nothing |
| 5 | Open Settings → Operators as an **admin** | The link is listed, with your operator name and the author name the reviews recorded — neither shown as a bare opaque id |
| 6 | Repeat steps 1–3 on a second platform with the same operator | Two chips. One human owns several accounts, and the totals cover both |
| 7 | Start a sign-in, then **decline** at the consent screen | Back on My activity saying you declined. Nothing linked |
| 8 | Start a sign-in, wait past 15 minutes, then approve | Refused as expired, with an instruction to start again |
| 9 | As a viewer, request `/api/scm-oauth-apps` | 403. Setting up applications is configuration, and configuration is admin-only including its reads |

### The one that is worth doing deliberately

Step 4 is the whole feature. Everything else can look right while the stored id is subtly wrong —
a username instead of a numeric id, a truncated Bitbucket `account_id` — and the only symptom is an
activity screen that stays empty, which is indistinguishable from a person who has not been
reviewed yet.

### Symptoms

| Symptom | Cause |
|---|---|
| The button is absent and the screen says no platform is set up | No OAuth application saved for any platform — Setup step 3 |
| The platform refuses with a redirect-URI error | The address registered on the application does not match the one shown on the Operators screen exactly. Behind a proxy, check `SPIRE_PUBLIC_HOST` and the forwarded headers — the address is derived from the request |
| Back on My activity with *The platform refused the sign-in* | Client id or secret wrong, or the application was deleted on the platform. The platform's own words are deliberately not repeated: its error response echoes back what was sent, and one of those values is the client secret |
| *That sign-in belonged to a different session* | The callback arrived under a different operator's session — expected if you switched accounts mid-flow, and the refusal an intercepted callback URL would meet |
| Linked, but the activity screen stays empty | Step 4's failure. Compare the stored id against `review_status.author_id` for a review that platform produced |
| A self-managed instance links you to the wrong person | The API base URL is blank while the sign-in base is set, on a platform where they differ — the identify call went to the hosted service and matched whoever holds your name there |

### Cleanup

Unlink under Settings → Operators, and remove the application with the trash control beside it (or
`DELETE /api/scm-oauth-apps/{providerType}`). Revoke the application on the platform too — this
product never held your access token beyond the one call that read your profile, but the
application itself is a standing grant on the platform side.

## Mode Q — the software factory's M0 exit criteria against a real forge (ADR-029..039)

Proves both M0 exit criteria (`docs/factory/ROADMAP.md`) with a real model and a real forge. This is
the manual counterpart to `M0WalkingSkeletonTest` (`spire-run-worker`, `testServices` tier), which
proves the same chain — clone, sandbox, handoff, gate, push — against a self-built smart-HTTP origin
with a script harness, no model and no spend. This mode is what that test cannot be: a Codex run,
against a forge, authenticated as a machine account.

### Setup

1. **Images.** Neither image is published yet; build both locally:

   ```bash
   docker build -f deploy/agent/codex/Dockerfile -t spire-agent-codex:latest deploy/agent
   ./gradlew :spire-publisher:installDist && docker build -t spire-publisher:latest spire-publisher
   ```

2. **Stack.** `docker compose up -d` (Postgres + Redpanda), then the orchestrator and the run worker
   in dev mode: `./gradlew :spire-orchestrator:quarkusDev` and `./gradlew :spire-run-worker:quarkusDev`
   (`:34083`). The worker drives the local Docker daemon itself; nothing else needs to be up.

3. **A scratch repository** on the forge (written against GitHub; the clone URL derivation covers all
   three) with a `main` branch. Note its head: `git ls-remote <url> refs/heads/main`.

4. **The machine account.** A *separate* forge account with write access to that repository and a
   token that can push (ADR-038: the factory never pushes as the review bot). Register it with role
   `FACTORY` — the Providers screen does not expose the role yet, so use the API:

   ```bash
   curl -sS -X POST http://localhost:34080/api/providers -H 'content-type: application/json' -d '{
     "name":"factory-bot","type":"github","baseUrl":"https://api.github.com","workspace":"<owner>",
     "authKind":"bearer","secret":"<machine-account token>","enabled":true,"authors":[],
     "botUsername":"<machine-account login>","role":"FACTORY"}'
   ```

   A workspace may hold a `REVIEWER` row and a `FACTORY` row side by side; the role is part of every
   lookup's key, so neither path can be handed the other's token.

5. **The harness credential pool.** The run's model key comes from the factory's OWN pool, never
   from the LLM provider registry the reviewer uses. There is no fallback: with an empty pool the
   dispatch is refused, naming what to configure.

   That separation is the point rather than an inconvenience. This key goes into a container
   running an untrusted model on an untrusted work item at full shell access, where a
   prompt-injected agent can read its own environment — so one exfiltration must not disable
   reviews as well, and a spend spike from a leaked key must be distinguishable in the ledger.

   ```bash
   curl -sS -X POST http://localhost:34080/api/harness-credentials \
     -H 'content-type: application/json' -d '{
       "label":"codex-primary","type":"openai",
       "baseUrl":"https://api.openai.com","apiKey":"<sk-...>"}'
   ```

   Register two or more to see the rotation: the pool hands out the member that has rested
   longest, so repeated dispatches alternate. For the codex arm the key must be an OpenAI API key.

   `GET /api/harness-credentials` lists the pool — never the keys. A member can be rested
   (`POST /{id}/rest`), disabled (`DELETE /{id}`), brought back (`POST /{id}/enable`), or returned
   after a refusal (`POST /{id}/clear-rejection`).

   **`llmProviderId` on a dispatch is now a `400`.** The pool rotates, so a request that pinned a
   key would defeat it; refusing is deliberate, because honouring it silently would be worse.

   **Known gap, so a rejected key does not surprise you:** nothing in the pipeline reports a
   credential refusal yet, so a dead key is NOT taken out of rotation automatically. A run using
   it fails as `MODEL_UNAVAILABLE` or `AGENT_FAILED` and the pool hands it out again. Retire it by
   hand with `DELETE /{id}`. See
   `techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md`.

### Trigger — exit criterion 1

6. Dispatch a run whose prompt asks for an ordinary change:

   ```bash
   curl -sS -X POST http://localhost:34080/api/runs -H 'content-type: application/json' -d '{
     "workspace":"<owner>","slug":"<repo>","providerType":"github","baseCommit":"<head sha>",
     "subject":"m0-ordinary","harness":"codex","model":"gpt-5.6",
     "prompt":"Add a file NOTES.md containing one line: hello from the factory. Commit it."}'
   ```

   Expect `201 {"runId":"run::github:<owner>/<repo>:m0-ordinary:1"}`. `GET /api/runs/<runId>` reads
   `queued`, then `running`, then `succeeded` with `pushedRef: refs/heads/spire/m0-ordinary`.

### Observe — exit criterion 1

7. On the forge: branch `spire/m0-ordinary` exists, its commit contains `NOTES.md`, and the commit's
   **author is the machine account** (the init container sets the workspace identity from the clone
   credential's username; the push was authenticated with the same account).
8. `docker ps -a --filter label=dev.codespire.runId` is empty: the unit was destroyed after salvage.
9. **No credential anywhere.** Each must print nothing:

   ```bash
   docker image history spire-agent-codex:latest | grep -i -E 'key|token|secret'
   docker image history spire-publisher:latest  | grep -i -E 'key|token|secret'
   ```

   and, on a run that is still alive (dispatch another and be quick, or set a long prompt):

   ```bash
   docker inspect $(docker ps -q --filter label=dev.codespire.role=agent) | grep -i -E 'OPENAI|TOKEN|SECRET|password'
   ```

   `OPENAI_API_KEY` **is** expected on the agent container — that is the model credential, injected
   per run. `SPIRE_GIT_SECRET` / `SPIRE_CLONE_SECRET` must appear **only** on the publisher and init
   containers, never on the agent. The worker's own log must contain neither value; the redacting
   `toString`s on `ExecuteRun` and `Credentials.Scm` are what makes that true, and a `grep` for the
   token's last six characters across the worker log is the check.

### Trigger — exit criterion 2

10. Dispatch a second run whose prompt edits a CI file:

    ```bash
    ... "subject":"m0-ci","prompt":"Create .github/workflows/factory.yml containing a workflow named factory that runs echo on push. Commit it." ...
    ```

### Observe — exit criterion 2

11. `GET /api/runs/<runId>` reads `push_gate_refused` with `blockedPaths: [".github/workflows/factory.yml"]`
    and `pushedRef: null`. The forge has **no** `spire/m0-ci` branch. The attention bell shows
    `RUN_PUSH_GATE_REFUSED` naming the path; `POST /api/runs/<runId>/attention-ack` clears it.
12. The refusal is not a failure: `failureCause` is null. The run did what it was asked and the
    publisher declined to deliver it — the operator's next move is the paths, not a stack trace.

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| `409` naming `FACTORY` | No FACTORY-role provider for that workspace; a REVIEWER row does not count and is never used as a fallback |
| `409` naming `No harness credential is configured` | The pool is empty. Add one at `POST /api/harness-credentials`; the reviewer's LLM provider is deliberately not used as a fallback |
| `409` naming `Capacity returns at ...` | Every member is exhausted. The message says when the earliest rate limit lifts, and how many were refused outright and will NOT come back without a new key |
| `409` naming `were refused by their provider` | Every member was refused. Nothing recovers on its own — replace the keys, or `POST /{id}/clear-rejection` on one you have fixed |
| `400` naming `llmProviderId is no longer accepted` | The request pinned a credential. The pool chooses now; drop the field |
| `503` naming `could not be read` | A database fault reading the pool, NOT a missing credential. Nothing was dispatched and nothing was spent — do not add keys in response to it |
| `400` naming `spire.factory.agent-image` | The harness has no image configured in the orchestrator (`spire.factory.agent-image.<harness>`) |
| `failed` / `SANDBOX_UNREACHABLE`, init exit non-zero | The clone failed: wrong token, wrong base commit (must be reachable from the remote's branches), or `spire-publisher:latest` not built. The unit is left behind on purpose — `docker logs` the init container |
| `failed` / `PUBLISHER_MISCONFIGURED` | The publisher refused its own configuration: branch outside `spire/`, equal to the base, or a userinfo-bearing remote URL. The line on the publisher's stdout names the variable |
| Codex exits immediately, `no output` | The pool member's key was rejected or absent, and the key must be OpenAI's for this arm. Nothing retires it automatically (see step 5) — `DELETE /api/harness-credentials/{id}` and dispatch again |
| `succeeded` with `pushedRef: null` | The agent committed nothing — its bundle never existed. Read the agent container's log; the prompt may not have asked for a commit |

### Cleanup

Delete the branches on the forge, the FACTORY provider (`DELETE /api/providers/{id}`), and the
images if they are not wanted (`docker rmi spire-agent-codex:latest spire-publisher:latest`).
Volumes and containers are per run and already gone unless a salvage failed, in which case
`docker ps -a --filter label=dev.codespire.runId` lists what was preserved and why.
