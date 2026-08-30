import { apiFetch } from './auth';

export type ReviewStatus =
  | 'reviewing'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'superseded'
  // A spend or diff-size cap declined to run this review (ADR-025). Distinct from 'failed' on
  // purpose: a policy decision is not an outage, and every consumer of this union that treats the
  // two alike undoes the split. It is terminal and archivable.
  | 'refused'
  | 'observed';

export type StageState = 'done' | 'active' | 'pending' | 'failed';

/** The pull/merge request's own state — distinct from the review-processing `status`. */
export type PrState = 'OPEN' | 'MERGED' | 'CLOSED';

export interface ReviewSummary {
  id: string; // full reviewId, e.g. "review::acme/web#412" (display/metadata only)
  workspace: string; // e.g. "acme"
  slug: string; // repo slug, e.g. "web"
  repo: string; // display repo name = slug
  pr: number;
  title: string;
  author: string; // username
  authorId: string; // stable numeric/provider user id (Bitbucket account_id, GitHub/GitLab numeric id)
  branch: string; // source branch
  base: string; // destination branch
  sha: string; // commit hash (12-char on Bitbucket, 40-char on GitHub)
  htmlUrl: string; // the PR's web URL — provider badge falls back to its host when providerType is empty
  providerType: string; // stored SCM type: 'github' | 'gitlab' | 'bitbucket-cloud' | 'bitbucket-dc' | ''
  prState: PrState; // the PR/MR's own state — rendered as a badge distinct from `status`
  status: ReviewStatus;
  stage: number; // 0..6 index into [Received, Diff, Context, Review, Comments, Done]
  findings: number;
  blockerCount: number; // number of blocker-severity (critical) findings — drives the outcome badge
  carriedOverFindings: number; // how many of `findings` were already open before this run
  // Sum of the charge lines that COULD be priced (1/100,000 dollar). 0 means either no charge has
  // landed yet OR every priced charge was an asserted UNMETERED zero — `unpricedCalls` is what tells
  // those apart from "some calls have no known price yet"; never conflate a 0 here with unpriced.
  costMillicents: number;
  model: string; // model that produced the review, e.g. "gemini-3.1-pro-preview" ('' if none yet)
  llmType: string; // LLM vendor from the catalog: 'openai' | 'anthropic' | 'gemini' | '' (uncatalogued)
  updatedAt: string; // ISO-8601
  answering?: boolean; // transient: true while the bot is composing a follow-up reply
  // Distinct calls the ledger could not price — lets the UI tell "zero spend" apart from "some
  // calls have no known price yet" (never conflated into `costMillicents`).
  unpricedCalls: number;
  // ISO-8601 when this review was archived, null while it is live. A third dimension beside `status`
  // and `prState`, not a value of either: an archived review still reports that it completed or
  // failed, which is the statistic the row is retained for.
  archivedAt: string | null;
  // The model produced no usable result — unparseable, or cut off at its output limit. On the LIST
  // row and not only the detail page, because the list is where the symptom shows: a run that
  // reviewed nothing renders as done with no findings, byte-identical to a clean pass.
  degraded: boolean;
}

export interface Finding {
  sev: 'critical' | 'warning' | 'suggestion' | 'nit';
  loc: string;
  msg: string;
  threadRef?: string; // the SCM thread this finding owns (present when it has a conversation)
  // Absent for a finding the review produced from the diff — which is every row stored before
  // conversation findings existed. 'conversation' means a human filed it with /finding.
  origin?: 'conversation';
}

/**
 * A re-review's verdict on a prior finding, matched back to the original by location/message.
 * `sev` reuses the findings' own display slugs (not enum names). `status` is one of
 * 'resolved' | 'still open' | 'acknowledged' | 'superseded' | 'unchanged' (lower-case with
 * spaces). 'unchanged' means the follow-up commit never touched or affected this finding — no
 * thread interaction ever happened for it, so `resolvedThread` is always falsy for that status.
 * `threadRef` (when present) links back to the finding's SCM conversation thread;
 * `resolvedThread` is whether that thread was actually closed out.
 */
export interface ReconciliationItem {
  sev: Finding['sev'];
  loc: string;
  msg: string;
  status: string;
  note?: string;
  threadRef?: string;
  resolvedThread?: boolean;
  // Carried forward from the prior finding this verdict judges — 'conversation' when a human filed
  // it with /finding, absent for a review-derived one and for every row stored before this existed.
  // A filed finding is a fresh `Finding` for one round and one of these for every round after, so
  // the provenance has to live on both shapes or it survives a single round.
  origin?: 'conversation';
}

/** One message in a re-fetched SCM thread (full text, not the persisted preview). */
export interface ThreadMessage {
  author: string;
  text: string;
  fromBot: boolean;
}

/**
 * Re-fetch a finding's conversation thread from the SCM in full (ADR-011 — the full text is not
 * persisted, only re-fetched by reference). Throws on any non-2xx so callers can fall back to the
 * stored preview.
 */
export async function fetchThreadMessages(
  workspace: string,
  slug: string,
  pr: string | number,
  threadRef: string,
): Promise<ThreadMessage[]> {
  const res = await apiFetch(
    `/api/reviews/${workspace}/${slug}/${pr}/threads/${encodeURIComponent(threadRef)}`,
  );
  if (!res.ok) throw new Error(`Failed to load thread (${res.status})`);
  return res.json();
}

/** The neutral token-billing dimensions. Mirrors the server's TokenType. TOTAL is the degraded
 *  case — an unreconciled call's whole token count — and can never carry a rate. */
export type TokenType = 'INPUT' | 'CACHED_INPUT' | 'CACHE_WRITE' | 'OUTPUT' | 'REASONING' | 'TOTAL';

/** How a model's tokens are costed. UNKNOWN is a runtime outcome, never an operator's choice. */
export type PricingMode = 'METERED' | 'UNMETERED' | 'UNKNOWN';

/** Which paid call a charge belongs to. Mirrors the server's ChargeKind; stored and sent as the name. */
export type ChargeKind = 'REVIEW' | 'RECONCILE' | 'FOLLOWUP';

/**
 * One token dimension of one LLM call, priced. `costMillicents` is null exactly when `pricingMode` is
 * 'UNKNOWN' — never 0, which would be indistinguishable from an UNMETERED model's asserted zero.
 *
 * The per-token rate is not sent: a rate is operator-entered configuration, and configuration reads are
 * admin-only (ADR-022), while this payload is served to viewers. A cost without its rate is still
 * honest — `pricingMode` says which world the model is in — and the rate remains on the ledger row and
 * on the admin-only Models page.
 *
 * `callRef` is the ledger's own call identity — group by it, not by `pricedAt`, which two calls of one
 * review can share.
 */
export interface ChargeLineView {
  callRef: string;
  kind: ChargeKind;
  model: string;
  tokenType: TokenType;
  tokens: number;
  costMillicents: number | null;
  pricingMode: PricingMode;
  pricedAt: string; // ISO-8601
}

export interface ReviewEvent {
  ts: string; // absolute ISO-8601 instant (UTC) — rendered in the viewer's locale
  at: string; // friendly delta from review start, e.g. "+2m 3s", "+23h 57m"
  lane: 'integration' | 'command' | 'domain' | 'result';
  type: string;
  det: string;
  threadRef?: string; // the SCM thread a conversation turn belongs to
  threadKind?: 'finding' | 'summary' | 'mention'; // classification for nesting; absent for non-turns
  // 'path:line' when the thread sits in the diff. A conversation the bot didn't start has no finding
  // to nest under, so this is the only thing that shows it is anchored rather than general.
  loc?: string;
}

// The detail endpoint's Java record omits four ReviewSummary components — model, llmType,
// costMillicents, carriedOverFindings are list-only projections the detail payload never sends.
// `extends ReviewSummary` used to declare them anyway, so a component could read one off a detail
// payload, get `undefined`, and render as if the field were simply absent/zero — which is exactly
// how `unpricedCalls` shipped as a silent `undefined` before it was wired into the record. Omit<>
// makes the next such field a compile error instead of a repeat of that.
export interface ReviewDetail
  extends Omit<ReviewSummary, 'model' | 'llmType' | 'costMillicents' | 'carriedOverFindings'> {
  // findings/blockerCount (from ReviewSummary) stay this RUN's raw counts — the findings card's
  // "+ N more" math depends on that meaning. openFindings/openBlockers are the reconciled
  // currently-open counts (this run's new findings + still-open/unchanged reconciliation,
  // deduped) — the same figures the list row shows, driving the detail HEADER badge instead.
  openFindings: number;
  openBlockers: number;
  attempt: number; // pipeline run count (1 = first run; bumped by each bounded auto-retry)
  stages: StageState[]; // length 6, aligns to the 6 pipeline steps
  timings: string[]; // length 6, e.g. "0.8s" or ""
  findingsList: Finding[];
  reconciliation?: ReconciliationItem[]; // re-review verdicts against the prior run's findings
  chargeLines: ChargeLineView[]; // every priced token dimension of every LLM call, oldest first
  note: string | null; // observe/stalled/superseded explanation, may be empty
  errorDetail: string | null; // technical error behind a terminal failure (e.g. the LLM provider's message)
  events: ReviewEvent[];
}

/**
 * The reviews list. Archived rows are excluded unless asked for — they are retained work, not
 * current work, and the busiest screen in the app defaults to what is live.
 */
export async function fetchReviews(includeArchived = false): Promise<ReviewSummary[]> {
  const res = await apiFetch(includeArchived ? '/api/reviews?includeArchived=true' : '/api/reviews');
  if (!res.ok) throw new Error(`Failed to load reviews (${res.status})`);
  return res.json();
}

export async function fetchReviewDetail(
  workspace: string,
  slug: string,
  pr: string | number,
): Promise<ReviewDetail> {
  const res = await apiFetch(
    `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${encodeURIComponent(String(pr))}`,
  );
  if (!res.ok) throw new Error(`Failed to load review (${res.status})`);
  return res.json();
}

const reviewPath = (workspace: string, slug: string, pr: string | number) =>
  `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${encodeURIComponent(String(pr))}`;

/**
 * Archive a review: it leaves the live list and its pull request is retired, but nothing is
 * destroyed — the timeline, the findings and above all the recorded LLM spend all stay readable.
 *
 * <p>Not a DELETE, because it deletes nothing. A refusal (409) carries a sentence saying which of
 * the three refusals it is — already archived, still running — and what to do; `throwResponse`
 * surfaces that body rather than a generic message, which is the whole reason the endpoint builds a
 * response entity instead of a bare status.
 */
export async function archiveReview(
  workspace: string,
  slug: string,
  pr: string | number,
): Promise<void> {
  const res = await apiFetch(`${reviewPath(workspace, slug, pr)}/archive`, { method: 'POST' });
  if (!res.ok) await throwResponse(res, 'Failed to archive review');
}

/** Return an archived review to the live list. One statement server-side; archiving stamped nothing. */
export async function unarchiveReview(
  workspace: string,
  slug: string,
  pr: string | number,
): Promise<void> {
  const res = await apiFetch(`${reviewPath(workspace, slug, pr)}/unarchive`, { method: 'POST' });
  if (!res.ok) await throwResponse(res, 'Failed to unarchive review');
}

/** Re-run a review's pipeline on its stored commit (force restart; re-runs the LLM, re-posts). */
export async function rerunReview(
  workspace: string,
  slug: string,
  pr: string | number,
): Promise<void> {
  const res = await apiFetch(
    `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${encodeURIComponent(String(pr))}/rerun`,
    { method: 'POST' },
  );
  if (!res.ok) await throwResponse(res, 'Failed to re-run review');
}

export interface RegisterResult {
  reviewId: string;
  workspace: string;
  slug: string;
  pr: number;
}

/** Manually register a PR for review (no webhook). Body is a URL or ws+slug+pr. */
export async function registerPr(body: {
  url?: string;
  workspace?: string;
  slug?: string;
  pr?: number;
  // The SCM type resolved from the URL, so a workspace name shared across SCMs
  // resolves the right provider when registering by fields. Omitted when unknown.
  providerType?: string;
}): Promise<RegisterResult> {
  const res = await apiFetch('/api/reviews/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await errorMessage(res, 'Register failed'));
  return res.json();
}

export interface ResolvedUrl {
  workspace: string;
  slug: string;
  pr: number;
  providerRegistered: boolean;
  providerType: string | null;
  providerName: string | null;
}

/**
 * Parse a PR/MR URL on the backend (single source of truth for the URL shapes)
 * and report which registered provider would handle it. Throws on an
 * unparseable URL (HTTP 400) — callers treat that as "keep typing".
 */
export async function resolvePrUrl(url: string): Promise<ResolvedUrl> {
  const res = await apiFetch('/api/reviews/register/resolve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  });
  if (!res.ok) await throwResponse(res, 'Could not resolve the URL');
  return res.json();
}

// ---- SCM providers ----

export type AuthKind = 'bearer' | 'basic';

export interface ProviderView {
  id: string;
  name: string;
  type: string; // 'bitbucket-cloud' | 'github'
  baseUrl: string;
  workspace: string;
  authKind: AuthKind;
  authUsername: string | null;
  hasSecret: boolean; // whether a token is stored (the token itself is never returned)
  botAccountId: string;
  enabled: boolean;
  authors: string[];
  conversationLevel: string | null; // '' / null = inherit the global default
  createdAt: string;
  lastCheckAt: string | null;
  lastCheckOk: boolean | null;
  lastCheckError: string | null;
}

export interface ProviderInput {
  name: string;
  type: string;
  baseUrl: string;
  workspace: string;
  authKind: AuthKind;
  authUsername?: string | null;
  secret?: string; // omit/empty on edit = keep the stored token
  botAccountId?: string; // blank = auto-resolved server-side from the token owner
  enabled: boolean;
  authors: string[];
  conversationLevel?: string; // omit/'' = inherit the global default
}

/**
 * Build a concise error message from a failed response. An app error body (plain
 * text / JSON — e.g. "Repository must be owner/repo") is surfaced as-is; an HTML body
 * (a Quarkus dev-error page, a 502 from a down service, a proxy page) is NOT a
 * user-facing message, so it collapses to the fallback + status instead of dumping a
 * wall of markup into the UI.
 */
async function errorMessage(res: Response, fallback: string): Promise<string> {
  const withStatus = `${fallback} (${res.status})`;
  try {
    const contentType = res.headers.get('content-type') ?? '';
    const text = (await res.text()).trim();
    if (!text) return withStatus;
    if (contentType.includes('text/html') || text.startsWith('<')) return withStatus;
    return text.length > 300 ? `${text.slice(0, 300)}…` : text;
  } catch {
    return withStatus;
  }
}

/** Read the response body and throw a concise error (HTML pages collapse to status). */
async function throwResponse(res: Response, fallback: string): Promise<never> {
  throw new Error(await errorMessage(res, fallback));
}

export async function fetchProviders(): Promise<ProviderView[]> {
  const res = await apiFetch('/api/providers');
  if (!res.ok) return throwResponse(res, 'Failed to load providers');
  return res.json();
}

export async function createProvider(input: ProviderInput): Promise<ProviderView> {
  const res = await apiFetch('/api/providers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create provider');
  return res.json();
}

export async function updateProvider(id: string, input: ProviderInput): Promise<ProviderView> {
  const res = await apiFetch(`/api/providers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update provider');
  return res.json();
}

export async function deleteProvider(id: string): Promise<void> {
  const res = await apiFetch(`/api/providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete provider');
}

export interface ProviderCheck {
  ok: boolean;
  account: string | null; // token owner's username when ok
  detail: string | null; // safe failure reason when not ok
}

// Live connectivity check: contacts the SCM with the stored token (whoami).
export async function checkProvider(id: string): Promise<ProviderCheck> {
  const res = await apiFetch(`/api/providers/${encodeURIComponent(id)}/check`, { method: 'POST' });
  if (!res.ok) await throwResponse(res, 'Failed to check provider');
  return res.json();
}

export interface RepoCheck {
  ok: boolean;
  detail: string | null;
}

// Live check that a repo exists and is reachable with the provider's token (no webhook is created).
export async function verifyRepo(providerId: string, repo: string): Promise<RepoCheck> {
  const res = await apiFetch(`/api/providers/${encodeURIComponent(providerId)}/verify-repo`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repo }),
  });
  if (!res.ok) await throwResponse(res, 'Failed to verify repository');
  return res.json();
}

// ---- Webhook repositories (per-repo webhook registrations) ----

export type WebhookScope = 'repo' | 'org';

export interface WebhookRepoView {
  id: string;
  providerType: string; // 'github' | 'gitlab' | 'bitbucket-cloud'
  scope: WebhookScope; // 'repo' (target = owner/repo) | 'org' (target = owner)
  target: string; // owner/repo (repo scope) | owner (org scope)
  webhookKey: string; // the (non-secret) URL path segment
  hasSecret: boolean; // whether a secret is stored (never returned)
  enabled: boolean;
  createdAt: string;
}

export interface WebhookRepoInput {
  providerType: string; // 'github' | 'gitlab' | 'bitbucket-cloud'
  scope: WebhookScope;
  target: string; // owner/repo (repo scope) | owner (org scope)
  enabled: boolean;
}

/**
 * Create/rotate response: the saved registration plus its secret in plaintext — shown
 * exactly ONCE. The secret is minted server-side; list/get never return it (hasSecret only).
 */
export interface WebhookRepoSecret {
  repo: WebhookRepoView;
  secret: string;
}

export async function fetchWebhookRepos(): Promise<WebhookRepoView[]> {
  const res = await apiFetch('/gw/webhook-repos');
  if (!res.ok) return throwResponse(res, 'Failed to load webhook repositories');
  return res.json();
}

export async function createWebhookRepo(input: WebhookRepoInput): Promise<WebhookRepoSecret> {
  const res = await apiFetch('/gw/webhook-repos', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create webhook repository');
  return res.json();
}

export async function updateWebhookRepo(id: string, input: WebhookRepoInput): Promise<WebhookRepoView> {
  const res = await apiFetch(`/gw/webhook-repos/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update webhook repository');
  return res.json();
}

/** Mint a fresh secret for an existing registration — returned once (never on list/get). */
export async function rotateWebhookSecret(id: string): Promise<WebhookRepoSecret> {
  const res = await apiFetch(`/gw/webhook-repos/${encodeURIComponent(id)}/rotate-secret`, { method: 'POST' });
  if (!res.ok) return throwResponse(res, 'Failed to rotate webhook secret');
  return res.json();
}

export async function deleteWebhookRepo(id: string): Promise<void> {
  const res = await apiFetch(`/gw/webhook-repos/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete webhook repository');
}

// ---- Review mode (global observe/active toggle) ----

export type ReviewMode = 'observe' | 'active';

export interface ReviewModeView {
  mode: ReviewMode;
}

export async function getReviewMode(): Promise<ReviewModeView> {
  const res = await apiFetch('/api/settings/review-mode');
  if (!res.ok) return throwResponse(res, 'Failed to load review mode');
  return res.json();
}

export async function setReviewMode(mode: ReviewMode): Promise<ReviewModeView> {
  const res = await apiFetch('/api/settings/review-mode', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode }),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update review mode');
  return res.json();
}

// ---- conversation settings (global default level + turn cap + retry/backoff) ----

export type ConversationLevel = 'REPORT_ONLY' | 'EXPLAIN' | 'INTERACTIVE';

export interface ConversationSettings {
  level: ConversationLevel;
  turnCap: number;
  maxAttempts: number;
  backoffBaseMs: number;
  backoffFactor: number;
}

export async function getConversationSettings(): Promise<ConversationSettings> {
  const res = await apiFetch('/api/settings/conversation');
  if (!res.ok) return throwResponse(res, 'Failed to load conversation settings');
  return res.json();
}

export async function setConversationSettings(settings: ConversationSettings): Promise<ConversationSettings> {
  const res = await apiFetch('/api/settings/conversation', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update conversation settings');
  return res.json();
}

// ---- review settings (the review pipeline's own retry budget) ----

/** Deliberately separate from ConversationSettings: a review that exhausts its attempts ends as a
 *  failed review carrying the provider's error, while a follow-up answer dead-letters for replay. */
export interface ReviewSettings {
  maxAttempts: number;
  backoffBaseMs: number;
  backoffFactor: number;
}

export async function getReviewSettings(): Promise<ReviewSettings> {
  const res = await apiFetch('/api/settings/review');
  if (!res.ok) return throwResponse(res, 'Failed to load review settings');
  return res.json();
}

export async function setReviewSettings(settings: ReviewSettings): Promise<ReviewSettings> {
  const res = await apiFetch('/api/settings/review', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update review settings');
  return res.json();
}

// ---- spend caps (fleet-wide diff-size, spend and call limits) ----

/** Every field optional: `null` means unlimited for the four caps, or "use the default window"
 *  for the window itself. A `null` on write clears a previously stored limit back to that state —
 *  never a `0`, which is precisely how ADR-023's "unknown became zero" bug entered, and here would
 *  turn "no cap" into "cap of zero", refusing every review. */
export interface CapSettings {
  maxChangedFiles: number | null;
  maxDiffBytes: number | null;
  spendCapMillicents: number | null;
  callCap: number | null;
  windowMinutes: number | null;
}

export async function getCapSettings(): Promise<CapSettings> {
  const res = await apiFetch('/api/settings/caps');
  if (!res.ok) return throwResponse(res, 'Failed to load spend limits');
  return res.json();
}

export async function setCapSettings(settings: CapSettings): Promise<CapSettings> {
  const res = await apiFetch('/api/settings/caps', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update spend limits');
  return res.json();
}

// ---- LLM providers ----

export type LlmType = 'openai' | 'anthropic' | 'gemini';

export interface LlmProviderView {
  id: string;
  name: string;
  type: LlmType;
  baseUrl: string;
  model: string;
  temperature: number;
  maxTokens: number | null;
  hasApiKey: boolean; // the key is never returned
  enabled: boolean;
  isDefault: boolean;
  createdAt: string;
  lastCheckAt: string | null;
  lastCheckOk: boolean | null;
  lastCheckError: string | null;
}

export interface LlmProviderInput {
  name: string;
  type: LlmType;
  baseUrl: string;
  apiKey?: string; // omit/empty on edit = keep the stored key
  model: string;
  temperature?: number;
  maxTokens?: number | null;
  enabled?: boolean;
  isDefault?: boolean;
}

export async function fetchLlmProviders(): Promise<LlmProviderView[]> {
  const res = await apiFetch('/api/llm-providers');
  if (!res.ok) return throwResponse(res, 'Failed to load LLM providers');
  return res.json();
}

export async function createLlmProvider(input: LlmProviderInput): Promise<LlmProviderView> {
  const res = await apiFetch('/api/llm-providers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create LLM provider');
  return res.json();
}

export async function updateLlmProvider(id: string, input: LlmProviderInput): Promise<LlmProviderView> {
  const res = await apiFetch(`/api/llm-providers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update LLM provider');
  return res.json();
}

export async function setDefaultLlmProvider(id: string): Promise<LlmProviderView> {
  const res = await apiFetch(`/api/llm-providers/${encodeURIComponent(id)}/default`, { method: 'PUT' });
  if (!res.ok) return throwResponse(res, 'Failed to set default LLM provider');
  return res.json();
}

export async function deleteLlmProvider(id: string): Promise<void> {
  const res = await apiFetch(`/api/llm-providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete LLM provider');
}

// Live connectivity check: probes the stored API key against the provider.
export async function checkLlmProvider(id: string): Promise<{ ok: boolean; detail: string | null }> {
  const res = await apiFetch(`/api/llm-providers/${encodeURIComponent(id)}/check`, { method: 'POST' });
  if (!res.ok) throw new Error(`LLM provider check failed: ${res.status}`);
  return res.json();
}

// --- context providers (Jira, Confluence, GitHub Issues, GitLab Issues, repository code) --------

export type ContextType = 'jira' | 'confluence' | 'github-issues' | 'gitlab-issues' | 'code';
export type ContextAuthKind = 'basic' | 'bearer';

export interface ContextProviderView {
  id: string;
  name: string;
  type: ContextType;
  baseUrl: string;
  authKind: ContextAuthKind;
  username: string | null;
  projectKeys: string | null; // e.g. "ACME" — narrows candidate issue keys; null = accept all
  hasSecret: boolean; // the secret is never returned
  enabled: boolean;
  isDefault: boolean;
  createdAt: string;
  lastCheckAt: string | null;
  lastCheckOk: boolean | null;
  lastCheckError: string | null;
}

export interface ContextProviderInput {
  name: string;
  type: ContextType;
  baseUrl: string;
  authKind: ContextAuthKind;
  username?: string; // required for basic (account email); unused for bearer
  secret?: string; // omit/empty on edit = keep the stored secret
  projectKeys?: string; // space/comma-separated project keys; blank = accept every well-formed key
  enabled?: boolean;
  isDefault?: boolean;
}

export interface ContextPreviewItem {
  kind: string;
  title: string;
  body: string;
  uri: string | null;
}

export interface ContextPreviewResult {
  keys: string[]; // the issue keys that resolved from the input
  status: string; // OK | EMPTY | ERROR
  items: ContextPreviewItem[]; // exactly what a review would inject
  detail: string | null; // note when empty/errored
}

// Test the integration: resolve the input to a ticket via the pattern and fetch its context, live.
export async function previewContextProvider(id: string, text: string): Promise<ContextPreviewResult> {
  const res = await apiFetch(`/api/context-providers/${encodeURIComponent(id)}/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (!res.ok) return throwResponse(res, 'Failed to preview context');
  return res.json();
}

export async function fetchContextProviders(): Promise<ContextProviderView[]> {
  const res = await apiFetch('/api/context-providers');
  if (!res.ok) return throwResponse(res, 'Failed to load context providers');
  return res.json();
}

export async function createContextProvider(input: ContextProviderInput): Promise<ContextProviderView> {
  const res = await apiFetch('/api/context-providers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create context provider');
  return res.json();
}

export async function updateContextProvider(id: string, input: ContextProviderInput): Promise<ContextProviderView> {
  const res = await apiFetch(`/api/context-providers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update context provider');
  return res.json();
}

export async function deleteContextProvider(id: string): Promise<void> {
  const res = await apiFetch(`/api/context-providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete context provider');
}

export interface ContextProviderCheck {
  ok: boolean;
  account: string | null; // token owner's display name when ok
  detail: string | null; // safe failure reason when not ok
}

// Live connectivity check: contacts the source with the stored credential (/myself).
export async function checkContextProvider(id: string): Promise<ContextProviderCheck> {
  const res = await apiFetch(`/api/context-providers/${encodeURIComponent(id)}/check`, { method: 'POST' });
  if (!res.ok) await throwResponse(res, 'Failed to check context provider');
  return res.json();
}

// ---- LLM model catalog (with token pricing) ----

/** Which OpenAI parameter carries the output-token cap for a model. */
export type OutputTokenParam = 'MAX_TOKENS' | 'MAX_COMPLETION_TOKENS' | 'NONE';

export interface LlmModelView {
  id: string;
  type: LlmType;
  name: string; // wire model id, e.g. gpt-4o
  label: string;
  pricingMode: Exclude<PricingMode, 'UNKNOWN'>; // UNKNOWN is a runtime outcome, never a catalog entry
  rates: Partial<Record<Exclude<TokenType, 'TOTAL'>, number>>; // millicents per 1M tokens; empty under UNMETERED
  outputTokenParam: OutputTokenParam; // max_tokens (chat) vs max_completion_tokens (reasoning)
  supportsTemperature: boolean; // false = omit temperature (reasoning models)
  reasoningEffort: string | null; // low | medium | high, or null
  extraParams: Record<string, unknown>; // free-form pass-through params
  enabled: boolean;
  createdAt: string;
}

export interface LlmModelInput {
  type: LlmType;
  name: string;
  label: string;
  pricingMode: Exclude<PricingMode, 'UNKNOWN'>;
  rates: Partial<Record<Exclude<TokenType, 'TOTAL'>, number>>;
  outputTokenParam?: OutputTokenParam;
  supportsTemperature?: boolean;
  reasoningEffort?: string | null;
  extraParams?: Record<string, unknown>;
  enabled?: boolean;
}

export async function fetchLlmModels(): Promise<LlmModelView[]> {
  const res = await apiFetch('/api/llm-models');
  if (!res.ok) return throwResponse(res, 'Failed to load LLM models');
  return res.json();
}

export async function createLlmModel(input: LlmModelInput): Promise<LlmModelView> {
  const res = await apiFetch('/api/llm-models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create LLM model');
  return res.json();
}

export async function updateLlmModel(id: string, input: LlmModelInput): Promise<LlmModelView> {
  const res = await apiFetch(`/api/llm-models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update LLM model');
  return res.json();
}

export async function deleteLlmModel(id: string): Promise<void> {
  const res = await apiFetch(`/api/llm-models/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete LLM model');
}

// ---- dead-letter queue (cs.dlq) ----

export interface DlqEntry {
  id: string;
  kafkaKey: string | null;
  messageType: string;
  originalTopic: string;
  reason: string | null;
  payload: string; // may contain long encrypted ciphertext — truncate before rendering
  status: string;
  createdAt: string;
}

/** List dead-letter entries, newest first. `pending = false` returns replayed/discarded ones too. */
export async function getDlqEntries(pending = true): Promise<DlqEntry[]> {
  const res = await apiFetch(`/api/dlq?pending=${pending}`);
  if (!res.ok) return throwResponse(res, 'Failed to load dead-letter entries');
  return res.json();
}

/** Re-publish a dead-lettered message to its original topic and mark it replayed. */
export async function replayDlqEntry(id: string): Promise<DlqEntry> {
  const res = await apiFetch(`/api/dlq/${encodeURIComponent(id)}/replay`, { method: 'POST' });
  if (!res.ok) return throwResponse(res, 'Failed to replay dead-letter entry');
  return res.json();
}

/** Mark a dead-lettered message discarded — it will not be replayed. */
export async function discardDlqEntry(id: string): Promise<void> {
  const res = await apiFetch(`/api/dlq/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to discard dead-letter entry');
}

// ---- attention (operator-facing conditions needing action) ----

/** One operator-facing condition that is true right now. Mirrors the backend AttentionView. */
export interface AttentionItem {
  code: string;
  severity: 'BLOCKING' | 'WARNING';
  subject: string | null;
  message: string;
  action: string | null;
  /**
   * An API path to POST to acknowledge this row, or null when it is not dismissable. Only rows
   * describing a past event no fix can clear carry one — a condition the operator can actually
   * repair must not be silenceable, or a broken system could be made to look healthy.
   */
  dismiss: string | null;
}

/** Acknowledge one row. The server decides what that means; the UI just posts where it was told. */
export async function dismissAttention(path: string): Promise<void> {
  const res = await apiFetch(path, { method: 'POST' });
  if (!res.ok) throw new Error(`Dismiss failed: ${res.status}`);
}

export async function fetchAttention(): Promise<AttentionItem[]> {
  const res = await apiFetch('/api/attention');
  if (!res.ok) throw new Error(`Attention request failed: ${res.status}`);
  return res.json();
}

/** The gateway's own feed. Served by a different service, so it can fail independently. */
export async function fetchWebhookAttention(): Promise<AttentionItem[]> {
  const res = await apiFetch('/gw/webhook-repos/attention');
  if (!res.ok) throw new Error(`Webhook attention request failed: ${res.status}`);
  return res.json();
}

// ---- prompts (per-kind system/body templates, with a locked suffix + variable palette) ----

/** One `{{name}}` slot the body may reference — clickable in the editor's palette. */
export interface PromptVariable {
  name: string;
  required: boolean;
  fenced: boolean;
  maxTokens: number;
  description: string;
}

// The deployment-wide scope every prompt endpoint defaults to. Mirrors the orchestrator's
// PromptScope.GLOBAL — everything else is a "workspace/slug" repository scope.
export const GLOBAL_SCOPE = '*';

// Which row actually supplied a PromptView's system/body: this scope's own override, a fallback
// to the global override, or the built-in default. Not derivable from `customized` alone once a
// repo scope exists — a repo scope showing global's text is `customized: true` either way.
export type PromptInheritance = 'repo' | 'global' | 'default';

export interface PromptView {
  kind: string;
  scope: string; // the scope this view was resolved at (GLOBAL_SCOPE or "workspace/slug")
  inheritedFrom: PromptInheritance;
  customized: boolean; // false = showing the built-in default (not a stored override)
  system: string;
  body: string;
  updatedAt: string | null;
  palette: PromptVariable[];
  lockedSuffixPreview: string; // always appended server-side — shown read-only, never editable
  // Drift: baseKnown=false means this row predates ancestor tracking, so drift is unknown --
  // never treat that as "up to date". When baseKnown, baseSystem/baseBody are the ancestor
  // recorded at last save and currentDefaultSystem/currentDefaultBody are what ships now.
  baseKnown: boolean;
  defaultDrifted: boolean;
  currentDefaultSystem: string;
  currentDefaultBody: string;
  baseSystem: string | null;
  baseBody: string | null;
}

/** The assembled text a real review call would send, with variable slots annotated. */
export interface PromptPreview {
  system: string;
  user: string;
  errors: string[];
  // The review this was rendered against, or null for the annotated (no-data) preview.
  sampleReviewId: string | null;
  // Why a requested sample could not be rendered — shown beside the annotated fallback so an
  // empty-looking panel is never mistaken for a broken preview.
  unavailableReason: string | null;
}

export async function fetchPrompts(scope: string = GLOBAL_SCOPE): Promise<PromptView[]> {
  const res = await apiFetch(`/api/prompts?scope=${encodeURIComponent(scope)}`);
  if (!res.ok) return throwResponse(res, 'Failed to load prompts');
  return res.json();
}

export async function fetchPrompt(kind: string, scope: string = GLOBAL_SCOPE): Promise<PromptView> {
  const res = await apiFetch(`/api/prompts/${encodeURIComponent(kind)}?scope=${encodeURIComponent(scope)}`);
  if (!res.ok) return throwResponse(res, 'Failed to load prompt');
  return res.json();
}

/** Repositories this deployment has reviewed -- the scopes a prompt override can be written at. */
export async function fetchPromptScopes(): Promise<string[]> {
  const res = await apiFetch('/api/prompts/scopes');
  if (!res.ok) return throwResponse(res, 'Failed to load prompt scopes');
  return res.json();
}

/**
 * A rejected save's 400 body is a JSON array of validation messages (the orchestrator's
 * `PromptResource#badRequest` sets the raw list as the entity — not wrapped in an object).
 * Join them into one readable message; any other shape (HTML error page, empty body) falls
 * back to the generic single-line message like the rest of this file's error handling.
 */
async function saveErrorMessage(res: Response): Promise<string> {
  const fallback = `Failed to save prompt (${res.status})`;
  try {
    const text = (await res.text()).trim();
    if (!text) return fallback;
    const contentType = res.headers.get('content-type') ?? '';
    if (contentType.includes('text/html') || text.startsWith('<')) return fallback;
    try {
      const parsed = JSON.parse(text);
      if (Array.isArray(parsed) && parsed.length > 0 && parsed.every((m) => typeof m === 'string')) {
        return parsed.join('\n');
      }
    } catch {
      // Not a JSON array of messages — fall through and show the raw text below.
    }
    return text.length > 300 ? `${text.slice(0, 300)}…` : text;
  } catch {
    return fallback;
  }
}

export async function savePrompt(
  kind: string, system: string, body: string, scope: string = GLOBAL_SCOPE,
): Promise<PromptView> {
  const res = await apiFetch(`/api/prompts/${encodeURIComponent(kind)}?scope=${encodeURIComponent(scope)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ system, body }),
  });
  if (!res.ok) throw new Error(await saveErrorMessage(res));
  return res.json();
}

/** Reset a kind back to its built-in default. Callers must re-fetch to get the default text. */
export async function resetPrompt(kind: string, scope: string = GLOBAL_SCOPE): Promise<void> {
  const res = await apiFetch(`/api/prompts/${encodeURIComponent(kind)}?scope=${encodeURIComponent(scope)}`, {
    method: 'DELETE',
  });
  if (!res.ok) await throwResponse(res, 'Failed to reset prompt');
}

/**
 * Keep the customization, stop reporting drift: re-stamp the ancestor to what ships now. The
 * operator's saved system/body text is untouched -- callers must re-fetch to see the cleared
 * drift flags. Deliberately not a `resetPrompt` variant -- reset discards the customization,
 * this preserves it.
 */
export async function acceptPromptDefault(kind: string, scope: string = GLOBAL_SCOPE): Promise<void> {
  const res = await apiFetch(
    `/api/prompts/${encodeURIComponent(kind)}/accept-default?scope=${encodeURIComponent(scope)}`,
    { method: 'POST' },
  );
  if (!res.ok) await throwResponse(res, 'Failed to accept current default');
}

export async function previewPrompt(
  kind: string, system: string, body: string, reviewId?: string, scope: string = GLOBAL_SCOPE,
): Promise<PromptPreview> {
  const res = await apiFetch(`/api/prompts/${encodeURIComponent(kind)}/preview?scope=${encodeURIComponent(scope)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ system, body, reviewId }),
  });
  if (!res.ok) return throwResponse(res, 'Failed to preview prompt');
  return res.json();
}

// ---- review context (assembled context blob + live PR description) ----

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
  const res = await apiFetch(`/wk/review-context/${seg(workspace)}/${seg(slug)}/${pr}`);
  if (!res.ok) return throwResponse(res, 'Failed to load context');
  return res.json();
}

/** The pull request's description as it stands NOW — fetched live, so it may have been edited. */
interface DescriptionResponse {
  description: string | null;
}

export async function fetchPrDescription(
  workspace: string,
  slug: string,
  pr: number,
): Promise<string | null> {
  const res = await apiFetch(`/api/reviews/${seg(workspace)}/${seg(slug)}/${pr}/description`);
  if (!res.ok) return throwResponse(res, 'Failed to load the description');
  const body: DescriptionResponse = await res.json();
  return body.description ?? null;
}

// --- Analytics (P4 / FR-11) -------------------------------------------------

/**
 * Headline numbers for one lens.
 *
 * `dismissalRate` and `medianRoundsToResolve` are nullable on purpose: a rate of 0
 * asserts "this team dismisses nothing", which is a claim about them, while null says
 * nothing has been judged yet. The same distinction the nullable verdict rests on.
 */
export interface AnalyticsTotals {
  findings: number;
  judged: number;
  dismissed: number;
  resolved: number;
  dismissalRate: number | null;
  medianRoundsToResolve: number | null;
  reviews: number;
  suppressed: number;
}

/** One severity/category cell. `category` is null for findings the model did not label. */
export interface AnalyticsBreakdown {
  severity: string;
  category: string | null;
  raised: number;
  dismissed: number;
  resolved: number;
  unjudged: number;
}

export interface AnalyticsLens {
  totals: AnalyticsTotals;
  breakdown: AnalyticsBreakdown[];
}

/**
 * The caller's own activity.
 *
 * `linked: false` is a THIRD state beside empty and error — "we do not know who you
 * are", not "you have done nothing". Rendering it as an empty chart would be the
 * ADR-025 `refused` incident again, where a missing case defaulted into the
 * reassuring branch.
 */
export interface MyActivity {
  linked: boolean;
  /**
   * EVERY SCM account the caller owns, not one. A developer is routinely a GitHub id, a
   * GitLab id and a Bitbucket UUID at once, and reporting the first showed an arbitrary
   * slice of their work under the heading 'my activity'.
   */
  identities: OperatorIdentityLink[];
  totals: AnalyticsTotals | null;
  breakdown: AnalyticsBreakdown[];
}

/** An SCM account this deployment has actually reviewed — what an admin picks from. */
export interface ObservedAuthor {
  providerType: string;
  authorId: string;
  displayName: string;
  reviews: number;
}

export interface OperatorIdentityLink {
  oidcSubject: string;
  providerType: string;
  authorId: string;
}

export async function fetchAnalyticsOverview(): Promise<AnalyticsLens> {
  const res = await apiFetch('/api/analytics/overview');
  if (!res.ok) throw new Error(`Analytics overview failed: ${res.status}`);
  return res.json();
}

export async function fetchAnalyticsRepos(): Promise<string[]> {
  const res = await apiFetch('/api/analytics/repos');
  if (!res.ok) throw new Error(`Analytics repositories failed: ${res.status}`);
  return res.json();
}

export async function fetchAnalyticsRepo(workspace: string, slug: string): Promise<AnalyticsLens> {
  const res = await apiFetch(
    `/api/analytics/repos/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}`,
  );
  if (!res.ok) throw new Error(`Analytics repository failed: ${res.status}`);
  return res.json();
}

export async function fetchMyActivity(): Promise<MyActivity> {
  const res = await apiFetch('/api/analytics/me');
  if (!res.ok) throw new Error(`My activity failed: ${res.status}`);
  return res.json();
}

export async function fetchOperatorCandidates(): Promise<ObservedAuthor[]> {
  const res = await apiFetch('/api/operator-identities/candidates');
  if (!res.ok) throw new Error(`Author candidates failed: ${res.status}`);
  return res.json();
}

export async function fetchOperatorIdentities(): Promise<OperatorIdentityLink[]> {
  const res = await apiFetch('/api/operator-identities');
  if (!res.ok) throw new Error(`Operator identities failed: ${res.status}`);
  return res.json();
}

export async function linkOperatorIdentity(link: OperatorIdentityLink): Promise<void> {
  const res = await apiFetch('/api/operator-identities', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(link),
  });
  if (!res.ok) throw new Error((await res.text()) || `Link failed: ${res.status}`);
}

export async function unlinkOperatorIdentity(
  oidcSubject: string,
  providerType: string,
): Promise<void> {
  const res = await apiFetch(
    `/api/operator-identities/${encodeURIComponent(oidcSubject)}/${encodeURIComponent(providerType)}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(`Unlink failed: ${res.status}`);
}

// --- Learned memory (P4 / FR-10) --------------------------------------------

export interface LearnedPreference {
  id: number;
  scopeType: string;
  scopeValue: string;
  category: string;
  pathGlob: string;
  severity: string;
  state: 'PROPOSED' | 'APPROVED' | 'REJECTED';
  evidenceTotal: number;
  evidenceDismissed: number;
  /**
   * How many distinct reviews the evidence spans. Ten dismissals by one author on one
   * pull request look identical to ten across ten teams without it -- and an ACKNOWLEDGED
   * verdict comes from the model reading that author's own reply, so the evidence is
   * manufacturable by the person it would benefit.
   */
  evidenceReviews: number;
}

/**
 * The bar a proposal had to clear, carried so the card can show it beside the score.
 * A proposal whose threshold is invisible is a conclusion nobody can weigh — the rung-2
 * gate's failure, where a null from a corpus too thin to speak looked like a result.
 */
export interface MemoryThresholds {
  minEvidence: number;
  minDismissedPercent: number;
}

export interface MemoryView {
  preferences: LearnedPreference[];
  thresholds: MemoryThresholds;
}

export async function fetchMemory(): Promise<MemoryView> {
  const res = await apiFetch('/api/memory/preferences');
  if (!res.ok) throw new Error(`Learned memory failed: ${res.status}`);
  return res.json();
}

export async function decidePreference(
  id: number,
  action: 'approve' | 'reject' | 'revoke',
): Promise<void> {
  const res = await apiFetch(`/api/memory/preferences/${id}/${action}`, { method: 'POST' });
  if (!res.ok) throw new Error(`Could not ${action} the preference: ${res.status}`);
}

export async function rescanMemory(): Promise<number> {
  const res = await apiFetch('/api/memory/preferences/rescan', { method: 'POST' });
  if (!res.ok) throw new Error(`Rescan failed: ${res.status}`);
  return (await res.json()).proposed as number;
}
