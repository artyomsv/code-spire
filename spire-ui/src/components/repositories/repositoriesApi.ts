import { apiFetch } from '../../auth';

export interface RepositoryAccount {
  id: string;
  name: string;
  role: string;
  handle: string | null;
  state: string;
}
export interface Repository {
  id: string;
  scmType: string;
  forgeOrigin: string;
  workspace: string;
  slug: string;
  enabled: boolean;
  revision: number;
  reviewer: RepositoryAccount | null;
  factory: RepositoryAccount | null;
}
export interface RepositoryInput {
  scmType: string;
  forgeOrigin: string;
  workspace: string;
  slug: string;
  enabled: boolean;
  reviewerAccountId: string | null;
  factoryAccountId: string | null;
}
export interface PendingMapping {
  registrationId: string; revision: number; scmType: string;
  forgeOrigin: string | null; target: string; problem: string;
}
export async function fetchPendingMappings(): Promise<PendingMapping[]> {
  const response = await apiFetch('/api/repositories/pending');
  if (!response.ok) throw new Error('Could not load pending mappings');
  return response.json();
}
export async function linkMapping(pending: PendingMapping, repositoryId: string): Promise<void> {
  const response = await apiFetch(`/api/repositories/pending/${pending.registrationId}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repositoryId, revision: pending.revision }),
  });
  if (!response.ok) throw new Error(await response.text() || 'Could not link registration');
}
export async function fetchRepositories(): Promise<Repository[]> {
  const response = await apiFetch('/api/repositories');
  if (!response.ok) throw new Error('Could not load registered repositories');
  return response.json();
}
export async function fetchRepositoryKinds(): Promise<string[]> {
  const response = await apiFetch('/api/repositories/kinds');
  if (!response.ok) throw new Error('Could not load supported forge kinds');
  return response.json();
}
export async function saveRepository(input: RepositoryInput, initial: Repository | null): Promise<Repository> {
  const path = initial ? `/api/repositories/${initial.id}?revision=${initial.revision}` : '/api/repositories';
  const response = await apiFetch(path, {
    method: initial ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input),
  });
  if (!response.ok) throw new Error(await response.text() || 'Could not save repository');
  return response.json();
}
