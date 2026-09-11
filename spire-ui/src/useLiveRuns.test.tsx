import { StrictMode } from 'react';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import * as auth from './auth';
import { useLiveRuns } from './useLiveRuns';
import { RunSocket, runRow } from './test/liveRuns';

const older = runRow({ runId: 'run::github:TEST-WS/app:a:1' });
const newer = runRow({ runId: 'run::github:TEST-WS/app:b:1', startedAt: '2026-09-11T11:00:00Z' });

function pending<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  RunSocket.sockets = [];
  vi.stubGlobal('WebSocket', RunSocket);
  vi.spyOn(api, 'getRuns').mockResolvedValue([]);
  vi.spyOn(auth, 'fetchMe').mockResolvedValue(null);
  vi.spyOn(auth, 'isLeavingForAuth').mockReturnValue(false);
  vi.spyOn(auth, 'goToFullLogin').mockReturnValue(true);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('live runs', () => {
  it('requests an unfiltered newest-200 snapshot and opens the runs socket', async () => {
    vi.mocked(api.getRuns).mockResolvedValue([older, newer]);
    const { result } = renderHook(() => useLiveRuns());
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(api.getRuns).toHaveBeenCalledExactlyOnceWith({ limit: 200 });
    expect(RunSocket.latest.url).toBe(`ws://${location.host}/api/ws/runs`);
    expect(result.current.runs).toEqual([newer, older]);
  });

  it('replaces a matching row, inserts an unseen row and replaces the snapshot', async () => {
    const { result } = renderHook(() => useLiveRuns());
    await waitFor(() => expect(result.current.loading).toBe(false));
    RunSocket.push([older]);
    RunSocket.push(newer);
    const changed = { ...older, status: 'succeeded', prUrl: 'https://example.invalid/pr/42' };
    RunSocket.push(changed);
    expect(result.current.runs).toEqual([newer, changed]);
    RunSocket.push([older]);
    expect(result.current.runs).toEqual([older]);
    expect(api.getRuns).toHaveBeenCalledTimes(1);
  });

  it('orders ties by descending run id and preserves submillisecond queue order', () => {
    const { result } = renderHook(() => useLiveRuns());
    const a = runRow({ runId: 'a', startedAt: '2026-09-11T10:00:00.000123Z' });
    const b = runRow({ runId: 'b', startedAt: '2026-09-11T10:00:00.000123Z' });
    const c = runRow({ runId: '0', startedAt: '2026-09-11T10:00:00.000456Z' });
    RunSocket.push([a, c, b]);
    expect(result.current.runs).toEqual([c, b, a]);
  });

  it('ignores invalid JSON, unidentified rows and unrelated frames', () => {
    const { result } = renderHook(() => useLiveRuns());
    RunSocket.push([older, {}, { runId: 42 }, null]);
    RunSocket.raw('<html>unavailable</html>');
    for (const frame of [null, 42, 'hello', {}, { runId: false }]) RunSocket.push(frame);
    expect(result.current.runs).toEqual([older]);
    expect(result.current.error).toBeNull();
  });

  it('does not replace live rows with a late REST snapshot', async () => {
    const rest = pending<api.RunListEntry[]>();
    vi.mocked(api.getRuns).mockReturnValue(rest.promise);
    const { result } = renderHook(() => useLiveRuns());
    RunSocket.push([newer]);
    await act(async () => rest.resolve([older]));
    expect(result.current.runs).toEqual([newer]);
  });

  it('does not show a late REST failure over live data', async () => {
    const rest = pending<api.RunListEntry[]>();
    vi.mocked(api.getRuns).mockReturnValue(rest.promise);
    const { result } = renderHook(() => useLiveRuns());
    RunSocket.push([newer]);
    await act(async () => rest.reject(new Error('TEST-failure')));
    expect(result.current.error).toBeNull();
    expect(result.current.runs).toEqual([newer]);
  });

  it('reports a failed snapshot and clears the error when the feed recovers', async () => {
    vi.mocked(api.getRuns).mockRejectedValue(new Error('TEST-unavailable'));
    const { result } = renderHook(() => useLiveRuns());
    await waitFor(() => expect(result.current.error).toBe('TEST-unavailable'));
    RunSocket.push([older]);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(result.current.runs).toEqual([older]);
  });

  it('suppresses snapshot errors while logging out', async () => {
    vi.mocked(auth.isLeavingForAuth).mockReturnValue(true);
    vi.mocked(api.getRuns).mockRejectedValue(new Error('TEST-logout'));
    const { result } = renderHook(() => useLiveRuns());
    await act(async () => {});
    expect(result.current.error).toBeNull();
  });

  it('checks the session before reconnecting after 1500ms', async () => {
    vi.useFakeTimers();
    const session = pending<auth.Me | null>();
    vi.mocked(auth.fetchMe).mockReturnValue(session.promise);
    renderHook(() => useLiveRuns());
    await act(async () => RunSocket.latest.onclose?.({ code: 1006, reason: '' }));
    expect(auth.fetchMe).toHaveBeenCalledTimes(1);
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(RunSocket.sockets).toHaveLength(1);
    await act(async () => session.resolve(null));
    await act(async () => { vi.advanceTimersByTime(1499); });
    expect(RunSocket.sockets).toHaveLength(1);
    await act(async () => { vi.advanceTimersByTime(1); });
    expect(RunSocket.sockets).toHaveLength(2);
    expect(auth.goToFullLogin).not.toHaveBeenCalled();
  });

  it('goes to login without reconnecting when the session expired', async () => {
    vi.useFakeTimers();
    vi.mocked(auth.fetchMe).mockResolvedValue({ authEnabled: true, authenticated: false, roles: [], user: '' });
    renderHook(() => useLiveRuns());
    await act(async () => RunSocket.latest.onclose?.({ code: 1006, reason: '' }));
    expect(auth.goToFullLogin).toHaveBeenCalledTimes(1);
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(RunSocket.sockets).toHaveLength(1);
  });

  it('does not reconnect if logout begins during the session check', async () => {
    vi.useFakeTimers();
    const session = pending<auth.Me | null>();
    vi.mocked(auth.fetchMe).mockReturnValue(session.promise);
    renderHook(() => useLiveRuns());
    await act(async () => RunSocket.latest.onclose?.({ code: 1006, reason: '' }));
    vi.mocked(auth.isLeavingForAuth).mockReturnValue(true);
    await act(async () => session.resolve(null));
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(RunSocket.sockets).toHaveLength(1);
  });

  it('cancels a pending reconnect and ignores old frames after unmount', async () => {
    vi.useFakeTimers();
    const { unmount } = renderHook(() => useLiveRuns());
    const socket = RunSocket.latest;
    await act(async () => socket.onclose?.({ code: 1006, reason: '' }));
    unmount();
    expect(socket.closed).toBe(true);
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(RunSocket.sockets).toHaveLength(1);
  });

  it('ignores a disposed StrictMode socket', async () => {
    const { result } = renderHook(() => useLiveRuns(), { wrapper: StrictMode });
    expect(RunSocket.sockets).toHaveLength(2);
    expect(RunSocket.sockets[0].closed).toBe(true);
    RunSocket.push([newer]);
    act(() => RunSocket.sockets[0].onmessage?.({ data: JSON.stringify([older]) }));
    expect(result.current.runs).toEqual([newer]);
  });
});
