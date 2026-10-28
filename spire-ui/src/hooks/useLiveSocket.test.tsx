import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as auth from '../auth';
import { RunSocket } from '../test/liveRuns';
import { useLiveSocket, type LiveSocketOptions } from './useLiveSocket';

const SIGNED_IN: auth.Me = { authEnabled: true, authenticated: true, user: 'TEST-admin', roles: [] };
const SIGNED_OUT: auth.Me = { authEnabled: true, authenticated: false, user: '', roles: [] };

beforeEach(() => {
  RunSocket.sockets = [];
  vi.stubGlobal('WebSocket', RunSocket);
  vi.spyOn(auth, 'fetchMe').mockResolvedValue(SIGNED_IN);
  vi.spyOn(auth, 'ensureServiceSessions').mockResolvedValue(false);
  vi.spyOn(auth, 'isLeavingForAuth').mockReturnValue(false);
  vi.spyOn(auth, 'goToFullLogin').mockReturnValue(true);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function live(options: Partial<LiveSocketOptions> = {}) {
  const onFrame = vi.fn();
  const hook = renderHook(() => useLiveSocket({ path: '/api/ws/TEST-feed', onFrame, ...options }));
  return { onFrame, ...hook };
}

async function drop(code = 1006, reason = '') {
  await act(async () => RunSocket.latest.onclose?.({ code, reason }));
}

it.each([['http:', 'ws:'], ['https:', 'wss:']])('opens the matching socket scheme for %s on the given path', (protocol, scheme) => {
  vi.stubGlobal('location', { protocol, host: 'TEST.example:8443' });
  live();
  const url = new URL(RunSocket.latest.url);
  expect(url.protocol).toBe(scheme);
  expect(url.host).toBe('test.example:8443');
  expect(url.pathname).toBe('/api/ws/TEST-feed');
});

it('passes each parsed frame and drops one that is not JSON', () => {
  const { onFrame } = live();
  RunSocket.raw('<html>unavailable</html>');
  expect(onFrame).not.toHaveBeenCalled();
  RunSocket.push([1]);
  expect(onFrame).toHaveBeenCalledExactlyOnceWith([1]);
});

it('calls onOpen on every open and reports live', async () => {
  vi.useFakeTimers();
  const onOpen = vi.fn();
  const { result } = live({ onOpen });
  expect(result.current.status).toBe('connecting');
  RunSocket.open();
  expect(result.current.status).toBe('live');
  await drop();
  await act(async () => { vi.advanceTimersByTime(1500); });
  RunSocket.open();
  expect(onOpen).toHaveBeenCalledTimes(2);
  expect(result.current.status).toBe('live');
});

it('checks the session, then reconnects after 1500 ms and reports down', async () => {
  vi.useFakeTimers();
  const { result } = live();
  RunSocket.open();
  await drop();
  expect(auth.fetchMe).toHaveBeenCalledTimes(1);
  expect(result.current.status).toBe('down');
  await act(async () => { vi.advanceTimersByTime(1499); });
  expect(RunSocket.sockets).toHaveLength(1);
  await act(async () => { vi.advanceTimersByTime(1); });
  expect(RunSocket.sockets).toHaveLength(2);
});

it('goes to login without reconnecting when the session is gone', async () => {
  vi.useFakeTimers();
  vi.mocked(auth.fetchMe).mockResolvedValue(SIGNED_OUT);
  live();
  await drop();
  expect(auth.goToFullLogin).toHaveBeenCalledTimes(1);
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(RunSocket.sockets).toHaveLength(1);
});

// /api is signed in but the gateway's own session lapsed: fetchMe succeeds while every /gw handshake fails.
it('starts a lapsed service sign-in instead of reporting down', async () => {
  vi.useFakeTimers();
  vi.mocked(auth.ensureServiceSessions).mockResolvedValue(true);
  const { result } = live({ path: '/gw/ws/TEST-feed' });
  RunSocket.open();
  await drop();
  expect(auth.ensureServiceSessions).toHaveBeenCalledTimes(1);
  expect(result.current.status).not.toBe('down');
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(RunSocket.sockets).toHaveLength(1);
  expect(auth.goToFullLogin).not.toHaveBeenCalled();
});

it('stays closed with the reason when onClose says stop', async () => {
  vi.useFakeTimers();
  const onClose = vi.fn((close: { code: number }) => close.code === 1008 ? 'stop' as const : 'reconnect' as const);
  const { result } = live({ onClose });
  await drop(1008, 'TEST-no such run');
  expect(result.current.status).toBe('refused');
  expect(result.current.closeReason).toBe('TEST-no such run');
  expect(auth.fetchMe).not.toHaveBeenCalled();
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(RunSocket.sockets).toHaveLength(1);
});

it('ignores frames from a replaced socket', async () => {
  vi.useFakeTimers();
  const { onFrame } = live();
  const first = RunSocket.latest;
  await drop();
  await act(async () => { vi.advanceTimersByTime(1500); });
  expect(RunSocket.sockets).toHaveLength(2);
  act(() => first.onmessage?.({ data: JSON.stringify(['TEST-old']) }));
  expect(onFrame).not.toHaveBeenCalled();
});

it('closes on unmount and opens nothing after a pending reconnect', async () => {
  vi.useFakeTimers();
  const { unmount } = live();
  const socket = RunSocket.latest;
  await drop();
  unmount();
  expect(socket.closed).toBe(true);
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(RunSocket.sockets).toHaveLength(1);
});

it('opens nothing while disabled', () => {
  live({ enabled: false });
  expect(RunSocket.sockets).toHaveLength(0);
});

it('does not reconnect if logout begins during the session check', async () => {
  vi.useFakeTimers();
  let answer: (me: auth.Me) => void = () => {};
  vi.mocked(auth.fetchMe).mockReturnValue(new Promise(resolve => { answer = resolve; }));
  live();
  await drop();
  vi.mocked(auth.isLeavingForAuth).mockReturnValue(true);
  await act(async () => answer(SIGNED_IN));
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(RunSocket.sockets).toHaveLength(1);
});
