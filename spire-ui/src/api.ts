export type ReviewStatus =
  | 'reviewing'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'superseded'
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
  costMillicents: number; // review cost (1/100,000 dollar); 0 = unpriced/uncatalogued model
  model: string; // model that produced the review, e.g. "gemini-3.1-pro-preview" ('' if none yet)
  llmType: string; // LLM vendor from the catalog: 'openai' | 'anthropic' | 'gemini' | '' (uncatalogued)
  updatedAt: string; // ISO-8601
  answering?: boolean; // transient: true while the bot is composing a follow-up reply
}

export interface Finding {
  sev: 'critical' | 'warning' | 'suggestion' | 'nit';
  loc: string;
  msg: string;
  threadRef?: string; // the SCM thread this finding owns (present when it has a conversation)
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
  const res = await fetch(
    `/api/reviews/${workspace}/${slug}/${pr}/threads/${encodeURIComponent(threadRef)}`,
  );
  if (!res.ok) throw new Error(`Failed to load thread (${res.status})`);
  return res.json();
}

export interface Usage {
  model: string;
  prompt: string;
  completion: string;
  cost: string;
  latency: string;
}

/** One LLM call in a review's lifetime — the initial review generation, a conversation follow-up,
 *  or a re-review reconciliation pass. */
export interface LlmCall {
  kind: string; // 'review' | 'followup' | 'reconcile'
  model: string;
  tokensIn: number;
  tokensOut: number;
  costMillicents: number;
  createdAt?: string; // ISO-8601 timestamp of when the call happened
}

export interface ReviewEvent {
  ts: string; // absolute ISO-8601 instant (UTC) — rendered in the viewer's locale
  at: string; // friendly delta from review start, e.g. "+2m 3s", "+23h 57m"
  lane: 'integration' | 'command' | 'domain' | 'result';
  type: string;
  det: string;
  threadRef?: string; // the SCM thread a conversation turn belongs to
  threadKind?: 'finding' | 'summary' | 'mention'; // classification for nesting; absent for non-turns
}

export interface ReviewDetail extends ReviewSummary {
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
  usage: Usage | null;
  llmCalls: LlmCall[]; // every LLM call for this review, in call order (review generation + follow-ups)
  note: string | null; // observe/stalled/superseded explanation, may be empty
  errorDetail: string | null; // technical error behind a terminal failure (e.g. the LLM provider's message)
  events: ReviewEvent[];
}

export async function fetchReviews(): Promise<ReviewSummary[]> {
  const res = await fetch('/api/reviews');
  if (!res.ok) throw new Error(`Failed to load reviews (${res.status})`);
  return res.json();
}

export async function fetchReviewDetail(
  workspace: string,
  slug: string,
  pr: string | number,
): Promise<ReviewDetail> {
  const res = await fetch(
    `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${encodeURIComponent(String(pr))}`,
  );
  if (!res.ok) throw new Error(`Failed to load review (${res.status})`);
  return res.json();
}

/** Permanently delete a review and all of its data (row, timeline, event stream). */
export async function deleteReview(
  workspace: string,
  slug: string,
  pr: string | number,
): Promise<void> {
  const res = await fetch(
    `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${encodeURIComponent(String(pr))}`,
    { method: 'DELETE' },
  );
  if (!res.ok) await throwResponse(res, 'Failed to delete review');
}

/** Re-run a review's pipeline on its stored commit (force restart; re-runs the LLM, re-posts). */
export async function rerunReview(
  workspace: string,
  slug: string,
  pr: string | number,
): Promise<void> {
  const res = await fetch(
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
  const res = await fetch('/api/reviews/register', {
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
  const res = await fetch('/api/reviews/register/resolve', {
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
  const res = await fetch('/api/providers');
  if (!res.ok) return throwResponse(res, 'Failed to load providers');
  return res.json();
}

export async function createProvider(input: ProviderInput): Promise<ProviderView> {
  const res = await fetch('/api/providers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create provider');
  return res.json();
}

export async function updateProvider(id: string, input: ProviderInput): Promise<ProviderView> {
  const res = await fetch(`/api/providers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update provider');
  return res.json();
}

export async function deleteProvider(id: string): Promise<void> {
  const res = await fetch(`/api/providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete provider');
}

export interface ProviderCheck {
  ok: boolean;
  account: string | null; // token owner's username when ok
  detail: string | null; // safe failure reason when not ok
}

// Live connectivity check: contacts the SCM with the stored token (whoami).
export async function checkProvider(id: string): Promise<ProviderCheck> {
  const res = await fetch(`/api/providers/${encodeURIComponent(id)}/check`, { method: 'POST' });
  if (!res.ok) await throwResponse(res, 'Failed to check provider');
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
  const res = await fetch('/api/webhook-repos');
  if (!res.ok) return throwResponse(res, 'Failed to load webhook repositories');
  return res.json();
}

export async function createWebhookRepo(input: WebhookRepoInput): Promise<WebhookRepoSecret> {
  const res = await fetch('/api/webhook-repos', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create webhook repository');
  return res.json();
}

export async function updateWebhookRepo(id: string, input: WebhookRepoInput): Promise<WebhookRepoView> {
  const res = await fetch(`/api/webhook-repos/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update webhook repository');
  return res.json();
}

/** Mint a fresh secret for an existing registration — returned once (never on list/get). */
export async function rotateWebhookSecret(id: string): Promise<WebhookRepoSecret> {
  const res = await fetch(`/api/webhook-repos/${encodeURIComponent(id)}/rotate-secret`, { method: 'POST' });
  if (!res.ok) return throwResponse(res, 'Failed to rotate webhook secret');
  return res.json();
}

export async function deleteWebhookRepo(id: string): Promise<void> {
  const res = await fetch(`/api/webhook-repos/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete webhook repository');
}

// ---- Review mode (global observe/active toggle) ----

export type ReviewMode = 'observe' | 'active';

export interface ReviewModeView {
  mode: ReviewMode;
}

export async function getReviewMode(): Promise<ReviewModeView> {
  const res = await fetch('/api/settings/review-mode');
  if (!res.ok) return throwResponse(res, 'Failed to load review mode');
  return res.json();
}

export async function setReviewMode(mode: ReviewMode): Promise<ReviewModeView> {
  const res = await fetch('/api/settings/review-mode', {
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
  const res = await fetch('/api/settings/conversation');
  if (!res.ok) return throwResponse(res, 'Failed to load conversation settings');
  return res.json();
}

export async function setConversationSettings(settings: ConversationSettings): Promise<ConversationSettings> {
  const res = await fetch('/api/settings/conversation', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update conversation settings');
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
  const res = await fetch('/api/llm-providers');
  if (!res.ok) return throwResponse(res, 'Failed to load LLM providers');
  return res.json();
}

export async function createLlmProvider(input: LlmProviderInput): Promise<LlmProviderView> {
  const res = await fetch('/api/llm-providers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create LLM provider');
  return res.json();
}

export async function updateLlmProvider(id: string, input: LlmProviderInput): Promise<LlmProviderView> {
  const res = await fetch(`/api/llm-providers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update LLM provider');
  return res.json();
}

export async function setDefaultLlmProvider(id: string): Promise<LlmProviderView> {
  const res = await fetch(`/api/llm-providers/${encodeURIComponent(id)}/default`, { method: 'PUT' });
  if (!res.ok) return throwResponse(res, 'Failed to set default LLM provider');
  return res.json();
}

export async function deleteLlmProvider(id: string): Promise<void> {
  const res = await fetch(`/api/llm-providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete LLM provider');
}

// --- context providers (Jira, Confluence) --------------------------------------

export type ContextType = 'jira' | 'confluence';
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
  const res = await fetch(`/api/context-providers/${encodeURIComponent(id)}/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (!res.ok) return throwResponse(res, 'Failed to preview context');
  return res.json();
}

export async function fetchContextProviders(): Promise<ContextProviderView[]> {
  const res = await fetch('/api/context-providers');
  if (!res.ok) return throwResponse(res, 'Failed to load context providers');
  return res.json();
}

export async function createContextProvider(input: ContextProviderInput): Promise<ContextProviderView> {
  const res = await fetch('/api/context-providers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create context provider');
  return res.json();
}

export async function updateContextProvider(id: string, input: ContextProviderInput): Promise<ContextProviderView> {
  const res = await fetch(`/api/context-providers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update context provider');
  return res.json();
}

export async function deleteContextProvider(id: string): Promise<void> {
  const res = await fetch(`/api/context-providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to delete context provider');
}

export interface ContextProviderCheck {
  ok: boolean;
  account: string | null; // token owner's display name when ok
  detail: string | null; // safe failure reason when not ok
}

// Live connectivity check: contacts the source with the stored credential (/myself).
export async function checkContextProvider(id: string): Promise<ContextProviderCheck> {
  const res = await fetch(`/api/context-providers/${encodeURIComponent(id)}/check`, { method: 'POST' });
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
  inputPriceMillicentsPerMillion: number; // millicents per 1M input tokens
  outputPriceMillicentsPerMillion: number;
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
  inputPriceMillicentsPerMillion: number;
  outputPriceMillicentsPerMillion: number;
  outputTokenParam?: OutputTokenParam;
  supportsTemperature?: boolean;
  reasoningEffort?: string | null;
  extraParams?: Record<string, unknown>;
  enabled?: boolean;
}

export async function fetchLlmModels(): Promise<LlmModelView[]> {
  const res = await fetch('/api/llm-models');
  if (!res.ok) return throwResponse(res, 'Failed to load LLM models');
  return res.json();
}

export async function createLlmModel(input: LlmModelInput): Promise<LlmModelView> {
  const res = await fetch('/api/llm-models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to create LLM model');
  return res.json();
}

export async function updateLlmModel(id: string, input: LlmModelInput): Promise<LlmModelView> {
  const res = await fetch(`/api/llm-models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update LLM model');
  return res.json();
}

export async function deleteLlmModel(id: string): Promise<void> {
  const res = await fetch(`/api/llm-models/${encodeURIComponent(id)}`, { method: 'DELETE' });
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
  const res = await fetch(`/api/dlq?pending=${pending}`);
  if (!res.ok) return throwResponse(res, 'Failed to load dead-letter entries');
  return res.json();
}

/** Re-publish a dead-lettered message to its original topic and mark it replayed. */
export async function replayDlqEntry(id: string): Promise<DlqEntry> {
  const res = await fetch(`/api/dlq/${encodeURIComponent(id)}/replay`, { method: 'POST' });
  if (!res.ok) return throwResponse(res, 'Failed to replay dead-letter entry');
  return res.json();
}

/** Mark a dead-lettered message discarded — it will not be replayed. */
export async function discardDlqEntry(id: string): Promise<void> {
  const res = await fetch(`/api/dlq/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) await throwResponse(res, 'Failed to discard dead-letter entry');
}
