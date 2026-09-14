import { apiFetch } from '../../auth';

export const phases = ['INTAKE', 'SPEC', 'PLAN', 'BUILD', 'VERIFY', 'DELIVER', 'REVIEW', 'LAND'] as const;
export type Phase = typeof phases[number];
export interface Limits {
  gateTtlSeconds: number; maxRunsPerItem: number; maxStepsPerPlan: number; maxWallClockSeconds: number;
  maxCostMillicents: number; maxCallsPerItem: number; protectedPaths: string[];
}
export interface Profile { id: string; name: string; version: number; precedence: number; modes: Record<Phase, string>; limits: Limits }
export interface Pin { id: string; version: number }
export interface Policy { revision: number; ceiling: Profile | null; mappings: Record<string, Profile> }
async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(`/api/work-policy${path}`, init);
  if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
  return response.json();
}
export const profiles = () => read<Profile[]>('/profiles');
export const saveProfile = (profile: Profile) => read<Profile>('/profiles', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(profile) });
export const policy = (id: string) => read<Policy>(`/repositories/${encodeURIComponent(id)}`);
export const savePolicy = (id: string, input: { revision: number; ceiling: Pin; mappings: Record<string, Pin> }) =>
  read<Policy>(`/repositories/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
