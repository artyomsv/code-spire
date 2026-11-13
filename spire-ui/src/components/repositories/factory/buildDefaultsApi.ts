import { apiFetch } from '../../../auth';
import { workRefusal } from '../../work-items/workReasons';

/** A repository's saved build setup. Saved outside the query cache (the Factory tab), so a cached answer may be old. */
export const buildDefaultsKey = (repositoryId: string) => ['build-defaults', repositoryId] as const;

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
  /** The checks verify runs, in order (M4). Absent from an older server. */
  verifyCommands?: string[];
  /** The limit for all checks together, in seconds. Absent from an older server. */
  verifyTimeoutSeconds?: number;
  /** How the specification and plan are made (M4 slice 3). Absent from an older server, which only copies. */
  preparation?: Preparation;
  updatedBy: string | null;
  updatedAt: string | null;
}
export type PayWith = 'API_KEY' | 'SUBSCRIPTION';
/** How a run pays, in the words the build setup and the review setting show. */
export const PAY_WITH_LABEL: Record<PayWith, string> = { API_KEY: 'an API key', SUBSCRIPTION: 'a subscription seat' };
/** `composed` copies the ticket as one step; `generated` writes a specification and plan with the build model. */
export type Preparation = 'composed' | 'generated';

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
  /** Every configured harness, review-only ones included. */
  harnesses: string[];
  /** The harnesses that may pay with a subscription seat. */
  subscriptionHarnesses: string[];
  /** The harnesses that may run builds. */
  buildHarnesses: string[];
  /** Per harness, the token types it can report. A model that cannot price one of them is refused. */
  reportedTypes: Record<string, string[]>;
  /** Per harness, the models its image says it runs. Absent from an older server. */
  models?: Record<string, HarnessModels>;
  /** The longest verify time limit a save accepts. Absent from an older server. */
  verifyMaxSeconds?: number;
  /** The preparations a save accepts here; `generated` only while the server offers it. Absent from an older server. */
  preparations?: Preparation[];
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
export const buildOptionsKey = (repositoryId: string) => ['build-options', repositoryId] as const;
export const buildOptions = (repository: string) => read<BuildOptions>(`${base(repository)}/build/options`);
export const saveBuildDefaults = (repository: string, input: { expectedRevision: number; baseBranch: string; harness: string; model: string; effort: string | null; payWith: PayWith;
  verifyCommands: string[]; verifyTimeoutSeconds: number; preparation?: Preparation }) =>
  read<BuildDefaults>(`${base(repository)}/build`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
export const repositoryBranchHead = (repository: string, branch: string) =>
  read<BranchHead>(`${base(repository)}/branch-head?branch=${encodeURIComponent(branch)}`);

// NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
// through a Claude subscription or to hold claude.ai tokens:
// https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
/** Whether the Subscription payment is offered to this harness. A list that did not arrive offers it to none. */
export const mayPayWithSubscription = (subscriptionHarnesses: string[] | undefined, harness: string): boolean =>
  (subscriptionHarnesses ?? []).includes(harness);

/**
 * Whether a subscription must become an API key on this harness: only when the list ARRIVED and leaves the harness
 * out. An older server sends no list, which refuses nothing, so a stored seat is kept (though not offered anew).
 * Checked at runtime because the wire may lack a field the type calls required.
 */
export const subscriptionRefused = (subscriptionHarnesses: string[] | undefined, harness: string): boolean =>
  Array.isArray(subscriptionHarnesses) && !subscriptionHarnesses.includes(harness);

/** Whether a model survives a change to this harness: only when the harness's own list says it runs it. */
export const harnessOffersModel = (models: Record<string, HarnessModels> | undefined, harness: string, model: string): boolean => {
  const known = models?.[harness];
  return known?.status === 'OK' && known.offered.some(offered => offered.slug === model);
};
