import { act } from '@testing-library/react';
import type { RunEvent, RunListEntry, RunView } from '../api';

/** Controllable browser transport shared by the run hook and its consuming screens. */
export class RunSocket {
  static sockets: RunSocket[] = [];
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { code: number; reason: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(public url: string) {
    RunSocket.sockets.push(this);
  }

  close() { this.closed = true; }

  static get latest(): RunSocket {
    const socket = RunSocket.sockets[RunSocket.sockets.length - 1];
    if (!socket) throw new Error('no run socket opened');
    return socket;
  }

  static push(value: unknown) {
    RunSocket.raw(JSON.stringify(value));
  }

  static raw(data: string) {
    act(() => RunSocket.latest.onmessage?.({ data }));
  }
}

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
    ...runRow(), providerType: 'github', workspace: 'TEST-WS', slug: 'app', subject: 'task', attempt: 1,
    baseBranch: 'main', baseCommit: 'TEST-base-sha', pushedAs: 'TEST-bot', unitId: null,
    taskSummary: 'TEST-task summary', blocked: [], failureDetail: null,
    spend: { priced: 0, unpricedLines: 0, tokensByType: {} }, ...overrides,
  };
}

export function runEvent(sequence: number, overrides: Partial<RunEvent> = {}): RunEvent {
  return { runId: runRow().runId, sequence, at: '2026-09-11T10:00:00Z',
    kind: 'OUTPUT', text: `TEST-event-${sequence}`, error: false, ...overrides };
}
