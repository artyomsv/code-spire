import { expect, it } from 'vitest';
import { filterById, journeyCells, nextAction, standing, type WorkItemRow } from './workJourney';

const ASSISTED = { INTAKE: 'auto', SPEC: 'auto', PLAN: 'approve', BUILD: 'auto', VERIFY: 'auto', DELIVER: 'draft_pr', REVIEW: 'auto', LAND: 'approve' };
function row(overrides: Partial<WorkItemRow> = {}): WorkItemRow {
  return { id: 'TEST-item', sourceId: 'TEST-source', repositoryId: 'TEST-repository', repository: 'TEST-owner/TEST-repo', issueKey: 'TEST-36',
    trackerUrl: 'https://TEST.example/36', generation: 1, phase: 'verify', workflowStatus: 'awaiting_input', reason: 'run_usage_unknown',
    profile: null, revision: 1, updatedAt: '2026-09-15T17:47:00Z', effectiveModes: ASSISTED, ...overrides };
}

// The live item #36 stopped at verify on an unpriced run: four phases done, verify blocked, the rest by who decides.
it('draws a blocked verify with the done phases before it and the deciders after it', () => {
  expect(journeyCells(row())).toEqual(['done', 'done', 'done', 'done', 'now-blocked', 'later-auto', 'later-auto', 'later-ask']);
});
it('marks a plan that waits for approval as waiting, not blocked', () => {
  expect(journeyCells(row({ phase: 'plan', workflowStatus: 'waiting_approval' })).slice(0, 4)).toEqual(['done', 'done', 'now-waiting', 'later-auto']);
});
it('says who decides a later phase is unknown when the modes are missing', () => {
  expect(journeyCells(row({ phase: 'intake', workflowStatus: 'not_eligible', effectiveModes: undefined }))).toEqual(
    ['now-ignored', 'later-unknown', 'later-unknown', 'later-unknown', 'later-unknown', 'later-unknown', 'later-unknown', 'later-unknown']);
});
it('shows an off phase as off', () => {
  expect(journeyCells(row({ phase: 'intake', workflowStatus: 'active', effectiveModes: { ...ASSISTED, BUILD: 'off' } }))[3]).toBe('later-off');
});
it('draws a completed item as done end to end', () => {
  expect(journeyCells(row({ phase: 'complete', workflowStatus: 'completed' }))).toEqual(Array(8).fill('done'));
});
// An unknown status must never read as progress.
it('treats an unknown status as stopped', () => {
  expect(standing('TEST-future')).toBe('stopped');
  expect(journeyCells(row({ workflowStatus: 'TEST-future' }))[4]).toBe('now-stopped');
});

it('sends an unpriced run to the model prices', () => {
  expect(nextAction(row())).toEqual({ label: 'Add missing prices', to: '/settings/llm' });
});
it('opens an open decision beside the list', () => {
  const gate = { id: 'TEST-gate', version: 1, state: 'OPEN' as const, phase: 'plan', generation: 1, itemRevision: 1, policyRevision: 1, artifact: null,
    openedAt: '2026-09-15T17:42:46Z', expiresAt: '2026-09-16T17:42:00Z', resolver: null, channel: null, note: null };
  expect(nextAction(row({ workflowStatus: 'waiting_approval', gate }))).toEqual({ label: 'Review the plan decision', to: '/work-items?filter=needs-you&decide=TEST-item' });
  // A decision that is no longer open offers nothing to answer.
  expect(nextAction(row({ workflowStatus: 'waiting_approval', gate: { ...gate, state: 'SUPERSEDED' } }))).toBeNull();
});
it('sends a missing or moved task to its preparation', () => {
  expect(nextAction(row({ phase: 'spec', reason: 'specification_required' }))?.label).toBe('Prepare the task');
  expect(nextAction(row({ phase: 'plan', reason: 'artifacts_changed' }))?.to).toBe('/work-items/TEST-item');
});
it('offers nothing when the item waits on nobody', () => {
  expect(nextAction(row({ workflowStatus: 'active', reason: 'phase_started' }))).toBeNull();
  expect(nextAction(row({ workflowStatus: 'not_eligible', reason: 'no_eligible_label' }))).toBeNull();
});

it('falls back to every item for an unknown filter', () => {
  expect(filterById('TEST-unknown').id).toBe('all');
  expect(filterById(null).statuses).toEqual([]);
  expect(filterById('needs-you').statuses).toEqual(['waiting_approval', 'awaiting_input', 'suspended']);
});
