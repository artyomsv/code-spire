import { describe, expect, it } from 'vitest';
import type { LandView } from '../../api';
import { filterById, journeyCells, landProgress, nextAction, standing, type LandItem, type WorkItemRow } from './workJourney';

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

// Unknown usage is a missing price or a missing measurement; the row cannot tell which.
it('sends an unknown run cost to the item rather than claiming a price is missing', () => {
  expect(nextAction(row())).toEqual({ label: 'Check the run cost', to: '/work-items/TEST-item' });
});
// A ticket deleted or moved mid-build stopped the item; it did not finish.
it('draws a retired item as stopped where it was, not as done', () => {
  expect(standing('retired')).toBe('stopped');
  expect(journeyCells(row({ phase: 'build', workflowStatus: 'retired' })).slice(2, 5)).toEqual(['done', 'now-stopped', 'later-auto']);
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

it('a verify result gate waits on a person and names its decision', () => {
  expect(filterById('needs-you').statuses).toContain('waiting_approval');
  const waiting = row({ phase: 'verify', workflowStatus: 'waiting_approval', reason: 'verify_failed',
    gate: { id: 'TEST-verify-gate', version: 1, state: 'OPEN', phase: 'verify', generation: 1, itemRevision: 9, policyRevision: 1,
      artifact: 'TEST-digest', openedAt: '2026-10-07T00:00:00Z', expiresAt: '2026-10-08T00:00:00Z', resolver: null, channel: null, note: null } });
  expect(nextAction(waiting)?.label).toBe('Review the verify decision');
});

// M4 land (spec §7.10): the Land step says where the merge stands, in the operator's words.
describe('land step', () => {
  const pullRequest = { number: 901, url: 'https://github.example.test/TEST/repo/pull/901', draft: false };
  const execution = { runId: 'TEST-run', build: { workItemId: 'TEST-item', generation: 1, buildAttemptId: 'TEST-attempt', preparationBinding: 'a'.repeat(64) },
    head: 'b'.repeat(40), verificationAttempt: null, pullRequest, reviewId: 'TEST-review', landing: null };
  const view = (overrides: Partial<LandView> = {}): LandView => ({ state: 'checking', reason: null, message: null, checks: null, method: 'squash', ticketRule: 'comment',
    branchDeleted: null, note: null, attempt: 0, attemptsAllowed: 3, fixInProgress: false, handedOver: false, stoppable: false, ...overrides });
  const landItem = (overrides: Partial<LandItem> = {}): LandItem => ({ phase: 'land', workflowStatus: 'active', reason: 'phase_started', gate: null,
    land: view(), progress: { execution }, people: [], ...overrides });
  const gate = { id: 'TEST-land-gate', version: 1, state: 'OPEN' as const, phase: 'land', generation: 1, itemRevision: 9, policyRevision: 1,
    artifact: 'b'.repeat(40), openedAt: '2026-10-22T00:00:00Z', expiresAt: '2026-10-23T00:00:00Z', resolver: null, channel: null, note: null };

  it('waits for your approval at an open land gate', () => {
    expect(landProgress(landItem({ workflowStatus: 'waiting_approval', reason: 'approval_required', gate, land: view({ state: 'handed' }) }))).toBe('Waiting for your approval');
  });
  it('reads checking, waiting n of m and merging while the land runs', () => {
    expect(landProgress(landItem())).toBe('Checking GitHub');
    expect(landProgress(landItem({ land: view({ state: 'waiting_checks', checks: { state: 'PENDING', done: 2, total: 5, failed: [] } }) })))
      .toBe('Waiting for checks (2 of 5 done)');
    expect(landProgress(landItem({ land: view({ state: 'merging' }) }))).toBe('Merging');
  });
  it('says who merged, and whether the branch is gone', () => {
    const landed = { ...execution, landing: { pullRequest, mergeCommit: 'c'.repeat(40), method: 'squash' as const, mergedBy: '900123', landedAt: '2026-10-22T12:00:00Z' } };
    const done = landItem({ phase: 'complete', workflowStatus: 'completed', reason: 'landed', progress: { execution: landed },
      land: view({ state: 'done', branchDeleted: true }), people: [{ providerUserId: '900123', handle: 'TEST-person', displayName: null }] });
    expect(landProgress(done)).toBe('Merged ccccccc by @TEST-person, branch deleted');
    const byFactory = { ...landed, landing: { ...landed.landing, mergedBy: 'factory' } };
    expect(landProgress({ ...done, progress: { execution: byFactory }, land: view({ state: 'done', branchDeleted: false }) }))
      .toBe('Merged ccccccc by the factory, branch not deleted');
  });
  it('counts a fix against the attempts allowed', () => {
    expect(landProgress(landItem({ phase: 'build', land: view({ state: 'fixing', reason: 'land_checks_failed', attempt: 1, attemptsAllowed: 3, fixInProgress: true }) })))
      .toBe('Fixing: a failed check, attempt 1 of 3');
  });
  it('says a hand-off needs you, with what GitHub said', () => {
    const text = landProgress(landItem({ workflowStatus: 'awaiting_input', reason: 'land_blocked',
      land: view({ state: 'handed', reason: 'land_blocked', message: 'TEST-Required review missing', handedOver: true }) }));
    expect(text).toMatch(/^Needs you: GitHub requires something only a person can give/);
    expect(text).toContain('TEST-Required review missing');
  });
  // The server says which reasons hand an item over; a reason the screen has no copy for yet still reads as needing you.
  it('says a hand-off needs you whenever the server says the item was handed over', () => {
    expect(landProgress(landItem({ workflowStatus: 'awaiting_input', reason: 'TEST-land-future-reason',
      land: view({ state: 'handed', reason: 'TEST-land-future-reason', handedOver: true }) }))).toMatch(/^Needs you: /);
    expect(landProgress(landItem({ workflowStatus: 'awaiting_input', reason: 'land_blocked',
      land: view({ state: 'handed', reason: 'land_blocked', handedOver: false }) }))).toBeNull();
  });
  it('says nothing before land has anything to say', () => {
    expect(landProgress(landItem({ phase: 'review', land: null }))).toBeNull();
  });
});
