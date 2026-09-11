import { StrictMode } from 'react';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import * as auth from '../auth';
import { RunSocket, runEvent, runRow } from '../test/liveRuns';
import { useRunTranscript } from './useRunTranscript';

beforeEach(() => {
  RunSocket.sockets = [];
  vi.stubGlobal('WebSocket', RunSocket);
  vi.spyOn(api, 'getRunTranscript').mockResolvedValue([]);
  vi.spyOn(auth, 'isLeavingForAuth').mockReturnValue(false);
  vi.spyOn(auth, 'fetchMe').mockResolvedValue(null);
  vi.spyOn(auth, 'goToFullLogin').mockReturnValue(true);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it.each([['http:', 'ws:'], ['https:', 'wss:']])('uses the encoded query id and matching socket for %s', async (protocol, expectedProtocol) => {
  vi.stubGlobal('location', { protocol, host: 'TEST.example:8443' });
  vi.mocked(api.getRunTranscript).mockResolvedValue([runEvent(1)]);
  const { result } = renderHook(() => useRunTranscript(runRow().runId));
  const socketUrl = new URL(RunSocket.latest.url);
  expect(socketUrl.protocol).toBe(expectedProtocol);
  expect(socketUrl.host).toBe('test.example:8443');
  expect(socketUrl.pathname).toBe('/api/ws/runs/transcript');
  expect(socketUrl.searchParams.get('runId')).toBe(runRow().runId);
  await waitFor(() => expect(result.current.events).toEqual([runEvent(1)]));
  expect(api.getRunTranscript).toHaveBeenCalledExactlyOnceWith(runRow().runId);
});

it('accepts snapshots and one-event arrays, ordering and deduplicating by sequence alone', () => {
  const { result } = renderHook(() => useRunTranscript(runRow().runId));
  RunSocket.push([runEvent(3, { at: '2026-09-01T00:00:00Z' }), runEvent(1)]);
  RunSocket.push([runEvent(2)]);
  RunSocket.push([runEvent(2, { text: 'TEST-duplicate' })]);
  expect(result.current.events.map((e) => e.sequence)).toEqual([1, 2, 3]);
  expect(result.current.events[1].text).toBe('TEST-event-2');
  expect(result.current.loading).toBe(false);
});

it('merges a late REST page without replacing events already streamed', async () => {
  let resolve!: (events: api.RunEvent[]) => void;
  vi.mocked(api.getRunTranscript).mockReturnValue(new Promise((done) => { resolve = done; }));
  const { result } = renderHook(() => useRunTranscript(runRow().runId));
  RunSocket.push([runEvent(2), runEvent(3)]);
  await act(async () => resolve([runEvent(1), runEvent(2)]));
  expect(result.current.events.map((e) => e.sequence)).toEqual([1, 2, 3]);
});

it('ignores malformed and other-run events without dropping free-string kinds', () => {
  const { result } = renderHook(() => useRunTranscript(runRow().runId));
  RunSocket.raw('{');
  RunSocket.push(runEvent(1));
  RunSocket.push([null, {}, runEvent(1, { sequence: 1.5 }), runEvent(2, { runId: 'other' }),
    runEvent(3, { kind: 'TEST-FUTURE' })]);
  expect(result.current.events).toEqual([runEvent(3, { kind: 'TEST-FUTURE' })]);
});

it('keeps only the newest 2000 sequences, even with out-of-order pages and reconnect duplicates', () => {
  const { result } = renderHook(() => useRunTranscript(runRow().runId));
  RunSocket.push(Array.from({ length: 2000 }, (_, i) => runEvent(i + 1)));
  expect(result.current.dropped).toBe(false);
  RunSocket.push([runEvent(2001)]);
  RunSocket.push([runEvent(1), runEvent(2000)]);
  expect(result.current.events).toHaveLength(2000);
  expect(result.current.events[0].sequence).toBe(2);
  expect(result.current.events[1999].sequence).toBe(2001);
  expect(result.current.dropped).toBe(true);
});

it.each(['no such run', 'a transcript tail must name its run'])('treats policy close "%s" as terminal, including a late REST result', async (reason) => {
  vi.useFakeTimers();
  let resolve!: (events: api.RunEvent[]) => void;
  vi.mocked(api.getRunTranscript).mockReturnValue(new Promise((done) => { resolve = done; }));
  const { result } = renderHook(() => useRunTranscript(runRow().runId));
  act(() => RunSocket.latest.onclose?.({ code: 1008, reason }));
  await act(async () => resolve([runEvent(1)]));
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(result.current.error).toBe(reason);
  expect(result.current.events).toEqual([]);
  expect(auth.fetchMe).not.toHaveBeenCalled();
  expect(RunSocket.sockets).toHaveLength(1);
});

it('checks the session and reconnects after 1500ms, retaining accumulated events', async () => {
  vi.useFakeTimers();
  const { result } = renderHook(() => useRunTranscript(runRow().runId));
  RunSocket.push([runEvent(1), runEvent(2)]);
  await act(async () => RunSocket.latest.onclose?.({ code: 1006, reason: '' }));
  expect(auth.fetchMe).toHaveBeenCalledTimes(1);
  await act(async () => { vi.advanceTimersByTime(1499); });
  expect(RunSocket.sockets).toHaveLength(1);
  await act(async () => { vi.advanceTimersByTime(1); });
  expect(RunSocket.sockets).toHaveLength(2);
  RunSocket.push([runEvent(2), runEvent(3)]);
  expect(result.current.events.map((e) => e.sequence)).toEqual([1, 2, 3]);
});

it('redirects on an expired session without reconnecting', async () => {
  vi.useFakeTimers();
  vi.mocked(auth.fetchMe).mockResolvedValue({ authEnabled: true, authenticated: false, roles: [], user: '' });
  renderHook(() => useRunTranscript(runRow().runId));
  await act(async () => RunSocket.latest.onclose?.({ code: 1006, reason: '' }));
  expect(auth.goToFullLogin).toHaveBeenCalledTimes(1);
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(RunSocket.sockets).toHaveLength(1);
});

it('cancels retries on unmount and ignores a disposed StrictMode socket', async () => {
  vi.useFakeTimers();
  const { result, unmount } = renderHook(() => useRunTranscript(runRow().runId), { wrapper: StrictMode });
  const disposed = RunSocket.sockets[0];
  expect(disposed.closed).toBe(true);
  RunSocket.push([runEvent(2)]);
  act(() => disposed.onmessage?.({ data: JSON.stringify([runEvent(1)]) }));
  expect(result.current.events).toEqual([runEvent(2)]);
  await act(async () => RunSocket.latest.onclose?.({ code: 1006, reason: '' }));
  unmount();
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(RunSocket.sockets).toHaveLength(2);
  expect(RunSocket.latest.closed).toBe(true);
});

it('reports REST errors, clears them on live data, and suppresses errors during logout', async () => {
  vi.mocked(api.getRunTranscript).mockRejectedValue(new Error('TEST-unavailable'));
  const { result, unmount } = renderHook(() => useRunTranscript(runRow().runId));
  await waitFor(() => expect(result.current.error).toBe('TEST-unavailable'));
  RunSocket.push([runEvent(1)]);
  expect(result.current.error).toBeNull();
  unmount();
  vi.mocked(auth.isLeavingForAuth).mockReturnValue(true);
  const other = renderHook(() => useRunTranscript(runRow().runId));
  await act(async () => {});
  expect(other.result.current.error).toBeNull();
});
