import { apiFetch } from '../../auth';
import type { ActorResult } from '../actorsApi';

export type WorkSourceType = 'GITHUB' | 'GITLAB' | 'JIRA';
export interface WorkSource {
  id: string; name: string; type: WorkSourceType; origin: string; projectId: string; scope: string;
  repositoryId: string; accountId: string; enabled: boolean; configuredEnabled: boolean;
  version: { source: number; account: number; repository: number };
  cursor: string | null; health: string; allowedActors: string[];
  repository: { workspace: string; slug: string };
}
export interface WorkSourceInput {
  name: string; type: WorkSourceType; origin: string; scope: string; repositoryId: string; accountId: string; enabled: boolean;
}
export interface WorkCapabilities { operations: string[]; detail: string; }
async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await apiFetch(`/api/work-sources${path}`, { method,
    ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
  if (!response.ok) throw new Error(await response.text() || 'The tracker request failed. Check the source and selected account.');
  if (response.status === 202 || response.status === 204) return undefined as T;
  return response.json();
}
export const fetchWorkSources = () => request<WorkSource[]>('');
export const createWorkSource = (input: WorkSourceInput) => request<WorkSource>('', 'POST', input);
export const editWorkSource = (source: WorkSource, input: { name: string; accountId: string; enabled: boolean }) =>
  request<WorkSource>(`/${source.id}`, 'PUT', { ...input, revision: source.version.source });
export const rescanWorkSource = (id: string) => request<void>(`/${id}/rescan`, 'POST');
export const workCapabilities = (id: string) => request<WorkCapabilities>(`/${id}/capabilities`);
export const resolveWorkActor = (id: string, handle: string) => request<ActorResult>(`/${id}/actors/resolve`, 'POST', { handle });
export const saveWorkActor = (source: WorkSource, handle: string, providerUserId: string) =>
  request<WorkSource>(`/${source.id}/actors`, 'POST', { handle, providerUserId, revision: source.version.source });
export const removeWorkActor = (source: WorkSource, id: string) =>
  request<WorkSource>(`/${source.id}/actors/${encodeURIComponent(id)}?revision=${source.version.source}`, 'DELETE');
