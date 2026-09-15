import * as policyApi from '../../work-items/workPolicyApi';
import { pin } from '../../work-items/policyInput';

export interface Preset {
  label: string;
  name: string;
  modes: Partial<Record<policyApi.Phase, string>>;
}

/**
 * The three profiles and labels docs/factory/AUTONOMY.md §2 defines, in order from most to least
 * restrictive. A phase the document omits is off, as it is everywhere else in the policy model.
 */
export const PRESETS: Preset[] = [
  { label: 'spire:suggest', name: 'suggest', modes: { INTAKE: 'auto', SPEC: 'auto', PLAN: 'auto' } },
  { label: 'spire:assisted', name: 'assisted', modes: {
    INTAKE: 'auto', SPEC: 'auto', PLAN: 'approve', BUILD: 'auto', VERIFY: 'auto', DELIVER: 'draft_pr', REVIEW: 'auto', LAND: 'approve' } },
  { label: 'spire:auto', name: 'autonomous', modes: {
    INTAKE: 'auto', SPEC: 'auto', PLAN: 'auto', BUILD: 'auto', VERIFY: 'auto', DELIVER: 'pr', REVIEW: 'auto', LAND: 'auto_if_green' } },
];

/**
 * The caps from the same section. The document names no approval lifetime, so this uses the one a
 * new profile already starts with on the Profiles screen.
 */
export const PRESET_LIMITS: policyApi.Limits = {
  gateTtlSeconds: 86400, maxRunsPerItem: 5, maxStepsPerPlan: 20, maxWallClockSeconds: 7200,
  maxCostMillicents: 2_000_000, maxCallsPerItem: 40, protectedPaths: ['**/security/**', '.github/**', 'deploy/**'],
};

export interface PresetPlan {
  preset: Preset;
  /** The current version of a profile that already has this name. It is used as it is, never changed. */
  existing: policyApi.Profile | null;
  /** The profile that will be created when none has this name. */
  created: policyApi.Profile | null;
  /** What the label already maps to on this repository, when it is not this preset. That mapping stays. */
  conflict: policyApi.Profile | null;
}

function current(profiles: policyApi.Profile[], name: string) {
  return profiles.filter(profile => profile.name === name).sort((a, b) => b.version - a.version)[0] ?? null;
}

/**
 * What applying the presets would do, before anything is written. Names and precedences are unique
 * on the server, so an existing name is reused and a new profile takes the first free precedence at or
 * above its slot — kept ascending, because when two labels apply the lowest precedence wins, and the
 * most restrictive preset should be the one that wins.
 */
export function planPresets(profiles: policyApi.Profile[], policy: policyApi.Policy, newId: () => string): PresetPlan[] {
  const taken = new Set(profiles.map(profile => profile.precedence));
  let floor = 0;
  return PRESETS.map((preset, index) => {
    const existing = current(profiles, preset.name);
    const mapped = policy.mappings[preset.label] ?? null;
    let created: policyApi.Profile | null = null;
    if (existing) floor = Math.max(floor, existing.precedence + 1);
    else {
      let precedence = Math.max(floor, (index + 1) * 10);
      while (taken.has(precedence)) precedence++;
      taken.add(precedence);
      floor = precedence + 1;
      const modes = Object.fromEntries(policyApi.phases.map(phase => [phase, preset.modes[phase] ?? 'off'])) as Record<policyApi.Phase, string>;
      created = { id: newId(), name: preset.name, version: 1, precedence, modes, limits: PRESET_LIMITS };
    }
    const target = existing ?? created!;
    const conflict = mapped && !(mapped.id === target.id) ? mapped : null;
    return { preset, existing, created, conflict };
  });
}

/**
 * Creates the missing profiles, then saves the repository policy with the chosen ceiling and every
 * preset label that is not already mapped to something else. Safe to repeat after a partial failure:
 * a profile created on the first attempt is found by name on the second.
 */
export async function applyPresets(repositoryId: string, policy: policyApi.Policy, plan: PresetPlan[], ceilingName: string) {
  const resolved = new Map<string, policyApi.Profile>();
  for (const step of plan) resolved.set(step.preset.name, step.existing ?? await policyApi.saveProfile(step.created!));
  const ceiling = resolved.get(ceilingName);
  if (!ceiling) throw new Error('Choose one of the presets as the ceiling.');
  const mappings: Record<string, policyApi.Pin> = Object.fromEntries(Object.entries(policy.mappings).map(([label, profile]) => [label, pin(profile)]));
  for (const step of plan) if (!step.conflict) mappings[step.preset.label] = pin(resolved.get(step.preset.name)!);
  return policyApi.savePolicy(repositoryId, { revision: policy.revision, ceiling: pin(ceiling), mappings });
}

