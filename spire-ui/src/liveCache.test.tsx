import { QueryClientProvider, useQuery, type QueryClient } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { expect, it, vi } from 'vitest';
import { invalidateLive, mergeLive, noticeLive, updateLive, upsertBy, writeLive } from './liveCache';
import { createQueryClient } from './queryClient';

const KEY = ['TEST-key'] as const;

function pending<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function read(queryFn: () => Promise<string[]>) {
  const client: QueryClient = createQueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const hook = renderHook(() => useQuery({ queryKey: KEY, queryFn }), { wrapper });
  return { client, ...hook };
}

// The race the socket hooks guarded by hand: a slower REST snapshot must never replace a newer frame.
it('keeps a frame written while the REST read is out, and drops the late answer', async () => {
  const rest = pending<string[]>();
  const { client, result } = read(() => rest.promise);
  act(() => writeLive(client, KEY, ['TEST-frame']));
  expect(result.current.data).toEqual(['TEST-frame']);
  await act(async () => rest.resolve(['TEST-rest']));
  expect(result.current.data).toEqual(['TEST-frame']);
});

it('drops a late REST failure after a frame', async () => {
  const rest = pending<string[]>();
  const { client, result } = read(() => rest.promise);
  act(() => writeLive(client, KEY, ['TEST-frame']));
  await act(async () => rest.reject(new Error('TEST-late failure')));
  expect(result.current.error).toBeNull();
  expect(result.current.data).toEqual(['TEST-frame']);
});

it('clears an earlier read error when a frame arrives', async () => {
  const { client, result } = read(() => Promise.reject(new Error('TEST-unavailable')));
  await waitFor(() => expect(result.current.error?.message).toBe('TEST-unavailable'));
  act(() => writeLive(client, KEY, ['TEST-frame']));
  expect(result.current.error).toBeNull();
  expect(result.current.data).toEqual(['TEST-frame']);
});

it('updates from undefined when nothing is cached', () => {
  const client = createQueryClient();
  const update = vi.fn((previous: string[] | undefined) => [...(previous ?? []), 'TEST-row']);
  updateLive(client, KEY, update);
  expect(update).toHaveBeenCalledWith(undefined);
  expect(client.getQueryData(KEY)).toEqual(['TEST-row']);
});

it('keeps a frame update while the REST read is out', async () => {
  const rest = pending<string[]>();
  const { client, result } = read(() => rest.promise);
  act(() => updateLive<string[]>(client, KEY, previous => [...(previous ?? []), 'TEST-row']));
  await act(async () => rest.resolve(['TEST-rest']));
  expect(result.current.data).toEqual(['TEST-row']);
});

it('re-reads an invalidated key that a screen shows', async () => {
  const queryFn = vi.fn().mockResolvedValue(['TEST-rest']);
  const { client, result } = read(queryFn);
  await waitFor(() => expect(result.current.data).toEqual(['TEST-rest']));
  act(() => { void invalidateLive(client, KEY); });
  await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
});

// A notice can arrive while the first read is out, and that read may have started before the change it announces.
it('starts a fresh read when invalidated while the first read is out', async () => {
  const first = pending<string[]>();
  const queryFn = vi.fn().mockReturnValueOnce(first.promise).mockResolvedValue(['TEST-after change']);
  const { client, result } = read(queryFn);
  await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(1));
  act(() => { void invalidateLive(client, KEY); });
  await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
  await act(async () => first.resolve(['TEST-before change']));
  await waitFor(() => expect(result.current.data).toEqual(['TEST-after change']));
});

// An append-only stream: the REST page holds older events than the live tail, so neither may drop the other.
it('merges into an entry whose REST read is still out, and the read keeps it', async () => {
  const rest = pending<string[]>();
  const client = createQueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const merged = (more: string[]) => [...new Set([...(client.getQueryData<string[]>(KEY) ?? []), ...more])].sort();
  const { result } = renderHook(() => useQuery({ queryKey: KEY, queryFn: async () => merged(await rest.promise) }), { wrapper });
  act(() => mergeLive<string[]>(client, KEY, (previous) => [...(previous ?? []), 'TEST-2']));
  expect(result.current.data).toEqual(['TEST-2']);
  await act(async () => rest.resolve(['TEST-1']));
  expect(result.current.data).toEqual(['TEST-1', 'TEST-2']);
});

it('replaces a matching row in place, appends an unseen one and leaves the input alone', () => {
  const rows = [{ id: 'a', v: 1 }, { id: 'b', v: 1 }];
  expect(upsertBy(rows, { id: 'a', v: 2 }, row => row.id)).toEqual([{ id: 'a', v: 2 }, { id: 'b', v: 1 }]);
  expect(upsertBy(rows, { id: 'c', v: 1 }, row => row.id)).toEqual([...rows, { id: 'c', v: 1 }]);
  expect(rows).toEqual([{ id: 'a', v: 1 }, { id: 'b', v: 1 }]);
});

it('noticeLive re-reads an idle query a screen shows', async () => {
  const queryFn = vi.fn().mockResolvedValue(['TEST-rest']);
  const { client, result } = read(queryFn);
  await waitFor(() => expect(result.current.data).toEqual(['TEST-rest']));
  act(() => noticeLive(client, { queryKey: KEY }));
  await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
});

// A burst of notices faster than the read must neither cancel it for ever nor start a second beside it, and the read
// already out may predate the change, so exactly one more follows it.
it('noticeLive lets a read already out finish, then reads once more', async () => {
  const first = pending<string[]>();
  const queryFn = vi.fn().mockReturnValueOnce(first.promise).mockResolvedValue(['TEST-after change']);
  const { client, result } = read(queryFn);
  await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(1));
  act(() => { noticeLive(client, { queryKey: KEY }); noticeLive(client, { queryKey: KEY }); noticeLive(client, { queryKey: KEY }); });
  expect(queryFn).toHaveBeenCalledTimes(1);
  await act(async () => first.resolve(['TEST-before change']));
  await waitFor(() => expect(result.current.data).toEqual(['TEST-after change']));
  expect(queryFn).toHaveBeenCalledTimes(2);
});

it('noticeLive leaves a query that matches nothing alone', async () => {
  const queryFn = vi.fn().mockResolvedValue(['TEST-rest']);
  const { client, result } = read(queryFn);
  await waitFor(() => expect(result.current.data).toEqual(['TEST-rest']));
  act(() => noticeLive(client, { queryKey: ['TEST-other key'] }));
  await act(async () => {});
  expect(queryFn).toHaveBeenCalledTimes(1);
});

// A waiting query that leaves the cache without settling must not keep a cache listener for the client's life.
it('noticeLive stops waiting for a query that is removed', async () => {
  const first = pending<string[]>();
  const { client } = read(() => first.promise);
  await waitFor(() => expect(client.isFetching({ queryKey: KEY })).toBe(1));
  act(() => noticeLive(client, { queryKey: KEY }));
  expect(client.getQueryCache().hasListeners()).toBe(true);
  act(() => client.removeQueries({ queryKey: KEY }));
  expect(client.getQueryCache().hasListeners()).toBe(false);
});
