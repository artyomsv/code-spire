import { apiFetch } from '../../auth';

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
async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(path, init);
  if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
  return response.json();
}
export const resolveArtifact = (id: string, key: string) => read<ArtifactReference>(`/api/work-items/${encodeURIComponent(id)}/preparation/reference?key=${encodeURIComponent(key)}`);
export const registerPreparation = (id: string, input: Omit<Preparation, 'registeredBy'> & { expectedRevision: number }) =>
  read<{ reason: string }>(`/api/work-items/${encodeURIComponent(id)}/preparation`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
