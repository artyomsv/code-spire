import * as api from './workPolicyApi';

export const pin = (profile: api.Profile): api.Pin => ({ id: profile.id, version: profile.version });
export const key = (profile: api.Pin) => `${profile.id}:${profile.version}`;

export interface MappingRow { label: string; profile: string }

/**
 * The save request, or the reason it cannot be made. A mapping row left completely blank carries no
 * intent — it is what "Add label mapping" leaves behind — so it is dropped. A row with only one half
 * filled is refused by its number, because guessing the other half would grant authority nobody chose.
 */
export function policyInput(revision: number, ceiling: string, rows: MappingRow[], profiles: api.Profile[]) {
  const chosen = profiles.find(value => key(value) === ceiling);
  if (!chosen) throw new Error('Choose a repository ceiling.');
  const filled = rows.map((row, index) => ({ label: row.label.trim(), profile: row.profile, number: index + 1 }))
    .filter(row => row.label || row.profile);
  const incomplete = filled.find(row => !row.label || !row.profile);
  if (incomplete) throw new Error(`Label ${incomplete.number} needs both a ticket label and a profile version.`);
  if (new Set(filled.map(row => row.label)).size !== filled.length) throw new Error('Each label needs one mapping.');
  const mappings: Record<string, api.Pin> = {};
  for (const row of filled) {
    const profile = profiles.find(value => key(value) === row.profile);
    if (!profile) throw new Error(`Label ${row.number} names a profile version that no longer exists. Choose another.`);
    mappings[row.label] = pin(profile);
  }
  return { revision, ceiling: pin(chosen), mappings };
}

/** The rows an existing policy starts an editor with. */
export const rowsOf = (policy: api.Policy): MappingRow[] =>
  Object.entries(policy.mappings).map(([label, profile]) => ({ label, profile: key(profile) }));
