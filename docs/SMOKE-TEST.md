# Smoke Test Runbook

Two modes: **A** proves the pipeline with zero external accounts (stubs); **B** reviews a real
Bitbucket Cloud PR with a real LLM. Do A first — it validates your local stack in ~2 minutes.

Prerequisites for both: JDK 25 (SDKMAN `25.0.3-tem`), Docker running.

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

### 4. Repo webhook

On the TEST repo: *Settings -> Webhooks -> Add*:
- URL: `https://<tunnel-host>/webhooks/bitbucket`
- Secret: generate one (e.g. `openssl rand -hex 24`) — you'll put the same value in `.env`
- Triggers: Pull request **Created**, **Updated**, **Comment created**, **Merged**, **Declined**

### 5. `.env`

Append to your Mode-A `.env` (drop the `SPIRE_SCM_STUB` line — this is a real SCM):
```bash
SPIRE_LLM_PROVIDER=openai-compatible

SPIRE_SCM_BITBUCKET_WEBHOOK_SECRET=<webhook-secret>     # presence enables the /webhooks/bitbucket edge

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

- No Jira/Confluence context yet; replies to bot comments are ingested but not answered; `/review`
  re-trigger is parsed but inactive (all P2).
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
- **Events:** "Let me select" → **Pull requests** (and **Issue comments** if you want `/review`)

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

## Context enrichment (Jira) — add-on to any mode

Any of the review modes above pulls linked-Jira context into the prompt once a context provider is
registered. It is additive: no provider registered → reviews run exactly as before (empty context).

1. **Register the provider:** Settings → **Context** → Add provider (or
   `POST /api/context-providers`): `type=jira`, `baseUrl=https://<your-site>.atlassian.net`,
   `authKind=basic`, `username=<account-email>`, `secret=<API token>`. Optionally set **project keys**
   (`ACME`) so only that project's keys are looked up (and a bare ticket number resolves in the
   test box). Save validates the credential against `GET /rest/api/2/myself` — a bad token is a 400 up
   front. The row shows a live **Connection** indicator; set it as the default.
2. **Test it (no PR needed):** Settings → Context → **Test**, enter a ticket number/key (or a PR title)
   → the tool resolves the key with the configured pattern, fetches it live, and previews the exact
   `ContextItem` a review would inject (`POST /api/context-providers/{id}/preview`).
3. **Reference a ticket:** open a PR/MR whose title or source branch contains a real issue key
   (`PROJ-123`), then Register it (Mode C/D) or let a webhook fire.
4. **Verify:** the review timeline shows `ContextRequested → ContextContributed(JIRA) → ContextAssembled`;
   the posted review reflects the ticket's intent. Under the hood the assembled context is stored
   encrypted in `worker.context_blob` (keyed by `review_id`) and referenced by the `GenerateReview`
   `contextRef`. Deleting or re-running the review clears its blob (no orphans).

A typo'd or unreachable key is skipped (not fatal); an auth failure records an `ERROR` contribution and
the review still runs without context. Self-managed Jira (Data Center): use `authKind=bearer` with a PAT.

### Cleanup

Remove the provider in Settings → Context (or `DELETE /api/context-providers/{id}`). Any context blobs
vanish with their reviews — no separate cleanup.
