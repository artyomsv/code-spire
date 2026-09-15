import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../../work-items/workPolicyApi';
import { applyPresets, planPresets, PRESET_LIMITS } from './presets';

const off: Record<api.Phase, string> = { INTAKE: 'off', SPEC: 'off', PLAN: 'off', BUILD: 'off', VERIFY: 'off', DELIVER: 'off', REVIEW: 'off', LAND: 'off' };
const existing = (name: string, precedence: number, version = 1): api.Profile => ({ id: `TEST-${name}`, name, version, precedence, modes: off, limits: PRESET_LIMITS });
const empty: api.Policy = { revision: 3, ceiling: null, mappings: {} };
let ids = 0;
const newId = () => `TEST-new-${++ids}`;
beforeEach(() => { ids = 0; });

describe('planPresets', () => {
  it('creates the three documented profiles in ascending precedence', () => {
    const plan = planPresets([], empty, newId);
    expect(plan.map(step => [step.preset.label, step.created?.name, step.created?.precedence])).toEqual([
      ['spire:suggest', 'suggest', 10], ['spire:assisted', 'assisted', 20], ['spire:auto', 'autonomous', 30]]);
    // Phases the document omits are off, never auto.
    expect(plan[0].created!.modes).toEqual({ ...off, INTAKE: 'auto', SPEC: 'auto', PLAN: 'auto' });
    expect(plan[1].created!.modes.PLAN).toBe('approve');
  });
  it('reuses a profile that already has the name, at its newest version, unchanged', () => {
    const plan = planPresets([existing('assisted', 20, 1), existing('assisted', 20, 4)], empty, newId);
    expect(plan[1].existing?.version).toBe(4);
    expect(plan[1].created).toBeNull();
  });
  it('steps past a precedence another profile already holds', () => {
    // Precedence is unique on the server; a collision would refuse the whole save.
    const plan = planPresets([existing('TEST-other', 10), existing('TEST-another', 11)], empty, newId);
    expect(plan.map(step => step.created!.precedence)).toEqual([12, 20, 30]);
  });
  it('keeps a label another profile already answers to and reports it', () => {
    const other = existing('TEST-other', 5);
    const plan = planPresets([other], { ...empty, mappings: { 'spire:auto': other } }, newId);
    expect(plan[2].conflict).toEqual(other);
    expect(plan[0].conflict).toBeNull();
  });
});

describe('applyPresets', () => {
  beforeEach(() => {
    vi.spyOn(api, 'saveProfile').mockImplementation(async profile => profile);
    vi.spyOn(api, 'savePolicy').mockImplementation(async (_id, input) => ({ revision: input.revision + 1, ceiling: null, mappings: {} }));
  });
  it('creates only what is missing and pins the chosen ceiling and the labels', async () => {
    const suggest = existing('suggest', 10, 2);
    const plan = planPresets([suggest], empty, newId);
    await applyPresets('TEST-repository', empty, plan, 'suggest');
    expect(api.saveProfile).toHaveBeenCalledTimes(2);
    expect(api.savePolicy).toHaveBeenCalledWith('TEST-repository', { revision: 3, ceiling: { id: 'TEST-suggest', version: 2 }, mappings: {
      'spire:suggest': { id: 'TEST-suggest', version: 2 }, 'spire:assisted': { id: 'TEST-new-1', version: 1 }, 'spire:auto': { id: 'TEST-new-2', version: 1 } } });
  });
  it('does not overwrite a label that already maps to something else', async () => {
    const other = existing('TEST-other', 5);
    const policy = { ...empty, mappings: { 'spire:auto': other } };
    await applyPresets('TEST-repository', policy, planPresets([other], policy, newId), 'assisted');
    expect(vi.mocked(api.savePolicy).mock.calls[0][1].mappings['spire:auto']).toEqual({ id: other.id, version: 1 });
  });
});
