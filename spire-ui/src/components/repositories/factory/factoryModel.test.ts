import { describe, expect, it } from 'vitest';
import type { Policy, Profile, Phase } from '../../work-items/workPolicyApi';
import type { WorkSource } from '../../work-items/workSourcesApi';
import { clamped, effectiveModes, readiness } from './factoryModel';

const off: Record<Phase, string> = { INTAKE: 'off', SPEC: 'off', PLAN: 'off', BUILD: 'off', VERIFY: 'off', DELIVER: 'off', REVIEW: 'off', LAND: 'off' };
const profile = (name: string, modes: Partial<Record<Phase, string>>): Profile => ({ id: `TEST-${name}`, name, version: 1, precedence: 1,
  modes: { ...off, ...modes }, limits: { gateTtlSeconds: 1, maxRunsPerItem: 0, maxStepsPerPlan: 0, maxWallClockSeconds: 0, maxCostMillicents: 0, maxCallsPerItem: 0, protectedPaths: [] } });
const source = (enabled: boolean, people: number): WorkSource => ({ id: `TEST-source-${enabled}-${people}`, name: 'TEST-source', type: 'GITHUB',
  origin: 'https://github.example.test', projectId: '1', scope: 'TEST-owner/TEST-repo', repositoryId: 'TEST-repository', accountId: 'TEST-account',
  enabled, configuredEnabled: enabled, version: { source: 1, repository: 1, account: 1 }, cursor: null, health: 'healthy',
  allowedPeople: Array.from({ length: people }, (_, index) => ({ providerUserId: `90000${index}`, handle: `TEST-person-${index}`, displayName: null })),
  repository: { workspace: 'TEST-owner', slug: 'TEST-repo' } });
const assisted = profile('assisted', { INTAKE: 'auto', PLAN: 'approve', BUILD: 'auto', DELIVER: 'pr', LAND: 'auto_if_green' });
const policy = (ceiling: Profile | null, labels: number): Policy => ({ revision: 1, ceiling,
  mappings: Object.fromEntries(Array.from({ length: labels }, (_, index) => [`TEST-label-${index}`, assisted])) });

describe('readiness', () => {
  it('is ready only when all four parts are set', () => {
    expect(readiness([source(true, 1)], policy(assisted, 1))).toMatchObject({ done: 4, ready: true });
    expect(readiness([], null)).toMatchObject({ source: false, people: false, ceiling: false, labels: false, done: 0, ready: false });
  });
  it('does not count people on a source that reads nothing', () => {
    // An allowlist on a disabled source admits no ticket, so it must not tick the people step.
    expect(readiness([source(false, 2)], policy(assisted, 1))).toMatchObject({ source: false, people: false, done: 2 });
  });
  it('counts people on any reading source when several exist', () => {
    expect(readiness([source(true, 0), source(true, 1)], policy(assisted, 1)).people).toBe(true);
  });
});

describe('effectiveModes', () => {
  it('takes the stricter mode of the label profile and the ceiling in every phase', () => {
    const ceiling = profile('ceiling', { INTAKE: 'auto', PLAN: 'auto', BUILD: 'approve', DELIVER: 'draft_pr', LAND: 'approve' });
    expect(effectiveModes(assisted, ceiling)).toEqual({ ...off, INTAKE: 'auto', PLAN: 'approve', BUILD: 'approve', DELIVER: 'draft_pr', LAND: 'approve' });
    expect(clamped(assisted, ceiling)).toBe(true);
  });
  it('leaves a profile inside its ceiling unchanged', () => {
    expect(effectiveModes(assisted, assisted)).toEqual(assisted.modes);
    expect(clamped(assisted, assisted)).toBe(false);
  });
  it('never renders an unknown mode as one that runs', () => {
    const odd = profile('odd', { INTAKE: 'TEST-unknown' });
    expect(effectiveModes(odd, null).INTAKE).toBe('off');
  });
});
