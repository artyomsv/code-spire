import type { RunEvent, RunListEntry, RunView } from '../api';

export { FakeSocket as RunSocket } from './fakeSocket';

export function runRow(overrides: Partial<RunListEntry> = {}): RunListEntry {
  return {
    runId: 'run::github:TEST-WS/app:task:1', status: 'queued', kind: 'BUILD',
    harness: 'TEST-harness', model: 'TEST-model', branch: 'spire/task', pushedRef: null,
    reviewId: null, findingRef: null, failureCause: null,
    startedAt: '2026-09-11T10:00:00Z', agentStartedAt: null, endedAt: null,
    cost: { millicents: null }, prUrl: null, prError: null, ...overrides,
  };
}

export function runView(overrides: Partial<RunView> = {}): RunView {
  return {
    ...runRow(), credentialLabel: 'TEST-pool-key', credentialType: 'openai',
    providerType: 'github', workspace: 'TEST-WS', slug: 'app', subject: 'task', attempt: 1,
    baseBranch: 'main', baseCommit: 'TEST-base-sha', pushedAs: 'TEST-bot', unitId: null,
    taskSummary: 'TEST-task summary', blocked: [], failureDetail: null,
    spend: { priced: 0, unpricedLines: 0, tokensByType: {} }, ...overrides,
  };
}

export function runEvent(sequence: number, overrides: Partial<RunEvent> = {}): RunEvent {
  return { runId: runRow().runId, sequence, at: '2026-09-11T10:00:00Z',
    kind: 'OUTPUT', text: `TEST-event-${sequence}`, error: false, ...overrides };
}
