import { apiFetch } from '../../../auth';
import { workRefusal } from '../../work-items/workReasons';

export type MergeMethod = 'merge' | 'squash' | 'rebase';
export type TicketRule = 'comment' | 'close';

/** How this repository lands a delivered pull request. `revision` 0 means no row: the operator's defaults. */
export interface LandSettings {
  revision: number;
  mergeMethod: MergeMethod;
  ticketRule: TicketRule;
  /** Agent fixes a refused merge may spend; 0 hands every refusal to the operator. */
  fixAttempts: number;
  updatedBy: string | null;
  updatedAt: string | null;
}

/** The methods GitHub allows. `checked` false: the forge did not answer, so all three are offered. */
export interface LandOptions { checked: boolean; allowed: MergeMethod[] }

export interface LandSettingsInput { expectedRevision: number; mergeMethod: MergeMethod; ticketRule: TicketRule; fixAttempts: number }

/** Refusals arrive as `{reason}`, the same shape the build setup and the work-item screens translate. */
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

const base = (repository: string) => `/api/repositories/${encodeURIComponent(repository)}/factory/land`;

export const landSettingsKey = (repositoryId: string) => ['land-settings', repositoryId] as const;
/** The merge methods GitHub allows: a forge read, made only while the land form is open. */
export const landOptionsKey = (repositoryId: string) => ['land-options', repositoryId] as const;
export const landSettings = (repository: string) => read<LandSettings>(base(repository));
export const landOptions = (repository: string) => read<LandOptions>(`${base(repository)}/options`);
export const saveLandSettings = (repository: string, input: LandSettingsInput) =>
  read<LandSettings>(base(repository), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
