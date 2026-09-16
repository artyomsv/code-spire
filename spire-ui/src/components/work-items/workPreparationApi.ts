import { apiFetch } from '../../auth';
import { workRefusal } from './workReasons';

export interface Artifact {
  location: { ref: { type: string; origin: string; projectId: string; issueId: string }; issueKey: string; link: string };
  sha256: string;
}
export interface Preparation {
  specification: Artifact; plan: Artifact; baseBranch: string; baseCommit: string; harness: string; model: string; registeredBy: string;
}
export interface ArtifactReference { artifact: Artifact; title: string }
export interface WorkBuild { attemptId: string; state: string; runId: string | null; reason: string | null; generation: number }
export interface WorkExecution {
  runId: string;
  build: { workItemId: string; generation: number; buildAttemptId: string; preparationBinding: string };
  head: string;
  verificationAttempt: string | null;
  pullRequest: { number: number; url: string; draft: boolean | null } | null;
  reviewId: string | null;
}
/**
 * A refusal is answered as `{reason, detail}`. The raw JSON used to reach the screen unchanged, so a
 * registration refused for a raw newline in the plan, for a stale specification digest, or for a
 * second step read the same: `409: {"reason":"single_step_plan_required"}`.
 */
async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(path, init);
  if (!response.ok) {
    const body = await response.text();
    let refusal: { reason?: string; detail?: string } = {};
    try { refusal = JSON.parse(body) as typeof refusal; } catch { /* not a refusal the API shaped */ }
    throw new Error(refusal.reason ? workRefusal(refusal.reason, refusal.detail ?? null) : `${response.status}: ${body}`);
  }
  return response.json();
}
/** The harness names this deployment has an agent image for; anything else is refused at dispatch. */
export interface PreparationOptions { harnesses: string[] }
export interface BranchHead { branch: string; commit: string }

export const resolveArtifact = (id: string, key: string) => read<ArtifactReference>(`/api/work-items/${encodeURIComponent(id)}/preparation/reference?key=${encodeURIComponent(key)}`);
/** What an approver is asked to approve, read from the tracker now, or the rule that makes it unusable. */
export interface PreparationEvidence {
  reason: string | null; detail: string | null; specification: string | null; instruction: string | null;
  /** The prepared versions these texts were read against. */
  specificationSha256?: string; planSha256?: string;
}
export const preparationEvidence = (id: string) => read<PreparationEvidence>(`/api/work-items/${encodeURIComponent(id)}/preparation/evidence`);
export const preparationOptions = (id: string) => read<PreparationOptions>(`/api/work-items/${encodeURIComponent(id)}/preparation/options`);
export const branchHead = (id: string, branch: string) =>
  read<BranchHead>(`/api/work-items/${encodeURIComponent(id)}/preparation/head?branch=${encodeURIComponent(branch)}`);
export const registerPreparation = (id: string, input: Omit<Preparation, 'registeredBy'> & { expectedRevision: number }) =>
  read<{ reason: string }>(`/api/work-items/${encodeURIComponent(id)}/preparation`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
