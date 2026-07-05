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
  sha: string; // short commit hash
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
