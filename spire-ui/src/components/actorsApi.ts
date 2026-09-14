import { apiFetch } from '../auth';

export interface Actor {
  providerUserId: string;
  handle: string | null;
  displayName: string | null;
}
export interface ActorDisplay extends Actor {
  resolvedAt: string | null;
  stale: boolean;
  effect: 'ALLOW' | 'DENY';
  revision: number;
}
export interface ActorPolicy { revision: number; actors: ActorDisplay[]; }
export interface ActorResult { status: 'FOUND' | 'SELECTION_REQUIRED'; actors: Actor[]; detail: string | null; }
export interface ActorInput { handle: string; providerUserId?: string; repositoryId?: string; revision?: number; effect?: 'ALLOW' | 'DENY'; }

async function request<T>(path: string, method = 'GET', body?: ActorInput): Promise<T> {
  const response = await apiFetch(path, { method, ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) });
  if (!response.ok) {
    const text = await response.text();
    let detail = text;
    try { detail = (JSON.parse(text) as { error?: string }).error ?? text; } catch { /* plain API error */ }
    throw new Error(detail || 'Could not read or save people. Retry after checking the selected account.');
  }
  return response.json();
}
export function actorPath(accountId: string, repositoryId?: string): string {
  return repositoryId ? `/api/repositories/${repositoryId}/fix-actors` : `/api/providers/${accountId}/actors`;
}
export async function fetchActors(path: string, refresh = false): Promise<ActorPolicy> {
  const result = await request<ActorPolicy | ActorDisplay[]>(`${path}?refresh=${refresh}`);
  return Array.isArray(result) ? { revision: 0, actors: result } : result;
}
export const resolveActor = (path: string, input: ActorInput) => request<ActorResult>(`${path}/resolve`, 'POST', input);
export const saveActor = (path: string, input: ActorInput) => request<ActorPolicy | ActorDisplay[]>(path, 'POST', input);
export const deleteActor = (path: string, actor: ActorDisplay, revision: number) => request(`${path}/${encodeURIComponent(actor.providerUserId)}?revision=${revision}`, 'DELETE');
export function actorLabel(actor: Actor): string {
  return actor.handle ? `@${actor.handle}` : actor.displayName || `Unresolved identity (${actor.providerUserId})`;
}
