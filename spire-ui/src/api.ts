export type ReviewStatus =
  | 'reviewing'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'superseded'
  | 'observed';

export type StageState = 'done' | 'active' | 'pending' | 'failed';

export interface ReviewSummary {
  id: string; // full reviewId, e.g. "review::acme/web#412" (display/metadata only)
  workspace: string; // e.g. "acme"
  slug: string; // repo slug, e.g. "web"
  repo: string; // display repo name = slug
  pr: number;
  title: string;
  author: string; // username
  branch: string; // source branch
  base: string; // destination branch
  sha: string; // commit hash (12-char on Bitbucket, 40-char on GitHub)
  htmlUrl: string; // the PR's web URL — the provider badge is derived from its host
  status: ReviewStatus;
  stage: number; // 0..6 index into [Received, Diff, Context, Review, Comments, Done]
  findings: number;
  updatedAt: string; // ISO-8601
}

export interface Finding {
  sev: 'critical' | 'warning' | 'suggestion' | 'nit';
  loc: string;
  msg: string;
}

export interface Usage {
  model: string;
  prompt: string;
  completion: string;
  cost: string;
  latency: string;
}

export interface ReviewEvent {
  at: string;
  lane: 'integration' | 'command' | 'domain' | 'result';
  type: string;
  det: string;
}

export interface ReviewDetail extends ReviewSummary {
  authorId: string;
  htmlUrl: string;
  stages: StageState[]; // length 6, aligns to the 6 pipeline steps
  timings: string[]; // length 6, e.g. "0.8s" or ""
  findingsList: Finding[];
  usage: Usage | null;
  note: string | null; // observe/stalled/superseded explanation, may be empty
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
}): Promise<RegisterResult> {
  const res = await fetch('/api/reviews/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let msg = `Register failed (${res.status})`;
    try {
      const text = await res.text();
      if (text) msg = text;
    } catch {
      /* keep default */
    }
    throw new Error(msg);
  }
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
  botAccountId: string;
  enabled: boolean;
  authors: string[];
}

/** Read the response body as text and throw it as an error (falls back to status). */
async function throwResponse(res: Response, fallback: string): Promise<never> {
  let msg = `${fallback} (${res.status})`;
  try {
    const text = await res.text();
    if (text) msg = text;
  } catch {
    /* keep default */
  }
  throw new Error(msg);
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
