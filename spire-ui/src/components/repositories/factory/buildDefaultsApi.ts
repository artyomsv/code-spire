import { apiFetch } from '../../../auth';
import { workRefusal } from '../../work-items/workReasons';

/** What a prepared task copies when nobody types it. `revision` 0 means this repository has none yet. */
export interface BuildDefaults {
  revision: number;
  baseBranch: string | null;
  harness: string | null;
  model: string | null;
  /** The thinking level, or null for the model's own default. Absent from an older server. */
  effort?: string | null;
  /** How builds pay: per token with an API key, or on a signed-in subscription. Absent from an older server. */
  payWith?: PayWith | null;
  updatedBy: string | null;
  updatedAt: string | null;
}
export type PayWith = 'API_KEY' | 'SUBSCRIPTION';

/** One model a harness can run, as its agent image declares it. */
export interface HarnessModel {
  slug: string;
  displayName: string;
  /** THIS model's own default thinking level. Models differ, so there is no global one. */
  defaultEffort: string;
  /** The thinking levels this model allows. */
  efforts: string[];
  visible: boolean;
  priority: number;
}

/**
 * The models a harness can run, and why there are none when there are none.
 *
 * <p>An empty list means four different things, so the status travels with it: UNKNOWN (the run worker
 * has not answered yet), NO_CATALOGUE (the image was built without one), UNREADABLE, IMAGE_UNAVAILABLE.
 */
export interface HarnessModels {
  status: 'OK' | 'UNKNOWN' | 'NO_CATALOGUE' | 'UNREADABLE' | 'IMAGE_UNAVAILABLE';
  offered: HarnessModel[];
}

export interface BuildOptions {
  harnesses: string[];
  /** Per harness, the token types it can report. A model that cannot price one of them is refused. */
  reportedTypes: Record<string, string[]>;
  /** Per harness, the models its image says it runs. Absent from an older server. */
  models?: Record<string, HarnessModels>;
}
/** `account` is the role whose account answered: a REVIEWER-read head is not proof the factory can push. */
export interface BranchHead { branch: string; commit: string; account: string }

/** Refusals arrive as `{reason}`, the same shape the work-item screens already translate. */
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

const base = (repository: string) => `/api/repositories/${encodeURIComponent(repository)}/factory`;

export const buildDefaults = (repository: string) => read<BuildDefaults>(`${base(repository)}/build`);
export const buildOptions = (repository: string) => read<BuildOptions>(`${base(repository)}/build/options`);
export const saveBuildDefaults = (repository: string, input: { expectedRevision: number; baseBranch: string; harness: string; model: string; effort: string | null; payWith: PayWith }) =>
  read<BuildDefaults>(`${base(repository)}/build`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
export const repositoryBranchHead = (repository: string, branch: string) =>
  read<BranchHead>(`${base(repository)}/branch-head?branch=${encodeURIComponent(branch)}`);
