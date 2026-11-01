import { apiFetch } from '../../auth';
import type { PayWith } from './factory/buildDefaultsApi';

/** Every registered repository. Other keys start with `build-defaults`, so invalidate this one `exact`. */
export const repositoriesKey = ['repositories'] as const;

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
  // The reviewer may post Approve / Request changes here (ADR-047); off means every review is a comment.
  reviewerMayApprove: boolean;
  // The LLM provider this repository's reviews and replies use; null means the global default.
  reviewLlmProviderId: string | null;
  // The harness this repository's reviews run on (harness reviews, step 5); null means the API.
  reviewHarness: ReviewHarness | null;
}
/** The harness half of "Review with": never set beside an API provider. */
export interface ReviewHarness {
  harness: string;
  model: string;
  /** The thinking level, or null for the model's own default. */
  effort: string | null;
  payWith: PayWith;
}
export interface RepositoryInput {
  scmType: string;
  forgeOrigin: string;
  workspace: string;
  slug: string;
  enabled: boolean;
  reviewerAccountId: string | null;
  factoryAccountId: string | null;
  reviewerMayApprove: boolean;
  reviewLlmProviderId: string | null;
  reviewHarness: ReviewHarness | null;
}
export interface PendingMapping {
  registrationId: string; revision: number; scmType: string;
  forgeOrigin: string | null; target: string; problem: string;
}
export const pendingMappingsKey = ['pending-mappings'] as const;

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
export const repositoryKindsKey = ['repository-kinds'] as const;

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
