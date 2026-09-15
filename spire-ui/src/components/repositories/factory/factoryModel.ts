import * as policyApi from '../../work-items/workPolicyApi';
import type { WorkSource } from '../../work-items/workSourcesApi';

export interface Readiness {
  source: boolean;
  people: boolean;
  ceiling: boolean;
  labels: boolean;
  /** How many of the four parts are set, for the list column. */
  done: number;
  ready: boolean;
}

/**
 * Whether each part of a repository's setup can actually let work start. A disabled source reads
 * nothing, and people only count on a source that reads, so a source switched off takes the people
 * step down with it rather than leaving a green tick that admits nothing.
 */
export function readiness(sources: WorkSource[], policy: policyApi.Policy | null): Readiness {
  const reading = sources.filter(source => source.enabled);
  const parts = {
    source: reading.length > 0,
    people: reading.some(source => source.allowedPeople.length > 0),
    ceiling: !!policy?.ceiling,
    labels: !!policy && Object.keys(policy.mappings).length > 0,
  };
  const done = Object.values(parts).filter(Boolean).length;
  return { ...parts, done, ready: done === 4 };
}

function vocabulary(phase: policyApi.Phase): string[] {
  if (phase === 'DELIVER') return ['off', 'draft_pr', 'pr'];
  if (phase === 'LAND') return ['off', 'approve', 'auto_if_green'];
  return ['off', 'approve', 'auto'];
}

/**
 * What a label actually runs once the ceiling has cut it back: per phase, the stricter of the two.
 * This mirrors WorkPolicy.select for display only — the server decides. A mode this screen does not
 * know ranks as off, so an unrecognised value can never render as a phase that runs.
 */
export function effectiveModes(profile: policyApi.Profile, ceiling: policyApi.Profile | null): Record<policyApi.Phase, string> {
  const result = {} as Record<policyApi.Phase, string>;
  for (const phase of policyApi.phases) {
    const words = vocabulary(phase);
    const rank = (modes: Record<policyApi.Phase, string> | undefined) => Math.max(0, words.indexOf(modes?.[phase] ?? 'off'));
    result[phase] = words[ceiling ? Math.min(rank(profile.modes), rank(ceiling.modes)) : rank(profile.modes)];
  }
  return result;
}

/** True when the ceiling takes anything away from what the label asked for. */
export function clamped(profile: policyApi.Profile, ceiling: policyApi.Profile | null): boolean {
  const effective = effectiveModes(profile, ceiling);
  return policyApi.phases.some(phase => effective[phase] !== (vocabulary(phase).includes(profile.modes[phase]) ? profile.modes[phase] : 'off'));
}
