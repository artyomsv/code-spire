# Smoke Test Runbook

**A** stub pipeline, zero external accounts; **B** real Bitbucket Cloud PR (webhook);
**C** real GitHub PR via manual Register PR (no webhook); **D** real GitLab MR via manual Register
PR (no webhook); **E** real GitHub PR via webhook (Tailscale Funnel); **F** real GitLab MR via
webhook (Tailscale Funnel); **G** provider-parity regression script (run the same scenarios on every
SCM); **H** the attention panel; **I** context provisioning across every provider type; **J** operator
authentication; **K** the LLM cost ledger. Do A first — it validates your local stack in ~2 minutes.

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
registered. **Do not rename or move the file** before S10's last round: a rename churns finding
identity, a separate known limitation (`techdebt/`), and mixing it in earlier muddies every verdict
after it.

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
a metered model."* Enter `0` instead of leaving it blank: the same message, because the client
validator treats an entered zero and an absent value identically (`Number(raw) > 0` fails either way).
Confirm the same refusal fires for Input alone.

Bypass the client (a direct `POST`/`PUT` to `/api/llm-models` with an `OUTPUT` rate of `0` or omitted)
to see the **server's own guard**, which exists precisely because the UI is a courtesy, not the
control: **400**, *"A METERED model needs a rate above zero for OUTPUT. If this model is self-hosted
and costs nothing to call, set its pricing mode to UNMETERED instead of entering a zero — a zero rate
and an unentered rate must stay distinguishable."*

### K-4 — deleting a model a provider uses is refused

Attempt to delete the catalogued model K-1's provider still names (Settings → LLM → Models → delete).

**Expected:** **409**, *"Model 'X' is in use by N LLM provider(s). Point them at another model
first."* The model remains in the catalog. Repoint the provider at a different model and retry —
deletion should now succeed.

### K-5 — renaming a model a provider uses is refused

Attempt to rename that same in-use model (edit its `name` field and save; leave every other field
alone).

**Expected:** **409**, *"Model 'X' is in use by N LLM provider(s). Point them at another model
first, then rename it."* The rename does not take effect — check the model's name in the list, or
the provider's `model` field, rather than trusting the toast alone. Renaming the model's label,
rates or any other field with the *same* name should still succeed; only the name itself is
guarded.

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
longer reachable from the UI at all — the courtesy the code comment at
`LlmProviderResource.java` describes.

### Cleanup

Delete any model/provider pair added only for this pass, in the order K-4 requires (repoint or delete
the provider before the model).
