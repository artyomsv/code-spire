import { QueryClientProvider, useQuery, type QueryKey } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { createQueryClient } from '../../queryClient';
import { FakeSocket } from '../../test/fakeSocket';
import { useWorkItemChanges } from './useWorkItemChanges';
import { workKeys } from './workQueries';

beforeEach(() => {
  FakeSocket.sockets = [];
  vi.stubGlobal('WebSocket', FakeSocket);
});

/** Every work-item read a screen may show, each counting how often it was read. */
const KEYS = {
  itemA: workKeys.item('TEST-a'),
  draftA: workKeys.draft('TEST-a', '1:3'),
  stepsA: workKeys.steps('TEST-a'),
  trackerA: workKeys.tracker('TEST-a'),
  itemB: workKeys.item('TEST-b'),
  listFirst: workKeys.list('all', 0),
  listSecond: workKeys.list('needs-you', 50),
  openDecisions: workKeys.approvals(false),
  pastDecisions: workKeys.approvals(true),
  evidenceA: workKeys.evidence('TEST-a', 'TEST-gate:1:TEST-s:TEST-p'),
} satisfies Record<string, QueryKey>;
type Name = keyof typeof KEYS;

function Read({ name, reads }: { name: Name; reads: Record<string, number> }) {
  useQuery({ queryKey: KEYS[name], queryFn: async () => { reads[name] = (reads[name] ?? 0) + 1; return name; } });
  return null;
}

function Feed({ enabled }: { enabled?: boolean }) {
  useWorkItemChanges({ enabled });
  return null;
}

async function showAll(enabled?: boolean) {
  const reads: Record<string, number> = {};
  const client = createQueryClient();
  render(<QueryClientProvider client={client}>
    {(Object.keys(KEYS) as Name[]).map(name => <Read key={name} name={name} reads={reads} />)}
    <Feed enabled={enabled} />
  </QueryClientProvider>);
  await waitFor(() => expect(Object.keys(reads)).toHaveLength(Object.keys(KEYS).length));
  return reads;
}

/** The reads that happened since `before`, by name. */
function since(before: Record<string, number>, after: Record<string, number>) {
  return Object.fromEntries(Object.keys(KEYS).map(name => [name, (after[name] ?? 0) - (before[name] ?? 0)]));
}

it('opens /api/ws/work-items', async () => {
  await showAll();
  expect(FakeSocket.latest.url).toMatch(/\/api\/ws\/work-items$/);
});

it('re-reads the named item, its draft, its steps, every list page and the decisions', async () => {
  const reads = await showAll();
  const before = { ...reads };
  FakeSocket.push({ changed: ['TEST-a'] });
  await waitFor(() => expect(since(before, reads).listSecond).toBe(1));
  await act(async () => {});
  expect(since(before, reads)).toEqual({ itemA: 1, draftA: 1, stepsA: 1, trackerA: 0, itemB: 0, listFirst: 1, listSecond: 1,
    openDecisions: 1, pastDecisions: 1, evidenceA: 0 });
});

it("re-reads everything but the forge reads on '*'", async () => {
  const reads = await showAll();
  const before = { ...reads };
  FakeSocket.push({ changed: '*' });
  await waitFor(() => expect(since(before, reads).itemB).toBe(1));
  await act(async () => {});
  expect(since(before, reads)).toEqual({ itemA: 1, draftA: 1, stepsA: 1, trackerA: 0, itemB: 1, listFirst: 1, listSecond: 1,
    openDecisions: 1, pastDecisions: 1, evidenceA: 0 });
});

// The server sends nothing on open, and a socket that was down missed its notices.
it('re-reads everything but the forge reads on every open', async () => {
  const reads = await showAll();
  const before = { ...reads };
  FakeSocket.open();
  await waitFor(() => expect(since(before, reads).itemA).toBe(1));
  FakeSocket.open();
  await waitFor(() => expect(since(before, reads).itemA).toBe(2));
  await act(async () => {});
  expect(since(before, reads).trackerA).toBe(0);
  expect(since(before, reads).evidenceA).toBe(0);
});

it('ignores a frame that is not a change notice', async () => {
  const reads = await showAll();
  const before = { ...reads };
  FakeSocket.push({ changed: 5 });
  FakeSocket.push([]);
  FakeSocket.push({ changed: [7] });
  await act(async () => {});
  expect(Object.values(since(before, reads)).every(count => count === 0)).toBe(true);
});

it('opens no socket while disabled', async () => {
  await showAll(false);
  expect(FakeSocket.sockets).toHaveLength(0);
});
