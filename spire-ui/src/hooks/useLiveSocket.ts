import { useEffect, useRef, useState } from 'react';
import { ensureServiceSessions, fetchMe, goToFullLogin, isLeavingForAuth, needsLogin } from '../auth';

/**
 * How long to wait before reopening a socket that dropped. Long enough not to hammer a service that is restarting,
 * short enough that a deploy does not leave a screen blind for long.
 */
export const RECONNECT_MS = 1500;

export interface LiveSocketOptions {
  /** Same-origin path, e.g. '/api/ws/runs' or '/gw/ws/webhook-attention'. */
  path: string;
  /** Called once per parsed JSON frame. */
  onFrame: (frame: unknown) => void;
  /** Called on every successful open, the first one included. */
  onOpen?: () => void;
  /**
   * Called on every close before any reconnect. 'stop' makes the close terminal: no reconnect, status 'refused',
   * and the reason kept. Default: 'reconnect'.
   */
  onClose?: (close: { code: number; reason: string }) => 'reconnect' | 'stop';
  /** False closes the socket and stops reconnecting. Default: true. */
  enabled?: boolean;
}

export interface LiveSocketState {
  status: 'connecting' | 'live' | 'down' | 'refused';
  /** The server's close reason once status is 'refused', e.g. "no such run". */
  closeReason: string | null;
}

const CONNECTING: LiveSocketState = { status: 'connecting', closeReason: null };

function socketUrl(path: string): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}${path}`;
}

/** The frame, or `undefined` for one that is not JSON: a malformed frame must not blank a feed that was working. */
function parseFrame(data: string): { frame: unknown } | undefined {
  try {
    return { frame: JSON.parse(data) };
  } catch {
    return undefined;
  }
}

/**
 * Why a socket dropped, decided before anything reconnects. An expired or missing session closes a handshake with no
 * useful code, so reconnecting blindly hammered the identity provider on every expiry (useLiveReviews, before this
 * hook). And signed in to /api does not mean signed in to /gw: each service keeps its own session, and a handshake
 * cannot follow the redirect that says so (useAttention, before this hook).
 *
 * @returns 'reconnect' when the session is fine; 'handled' when a sign-in started or the screen went away.
 */
async function diagnoseDrop(gone: () => boolean): Promise<'reconnect' | 'handled'> {
  const me = await fetchMe();
  if (gone()) return 'handled';
  if (needsLogin(me)) {
    goToFullLogin();
    return 'handled';
  }
  if (await ensureServiceSessions()) return 'handled';
  return gone() ? 'handled' : 'reconnect';
}

interface SocketWiring {
  callbacks: { readonly current: LiveSocketOptions };
  report: (state: LiveSocketState) => void;
  /** True once this socket was replaced or abandoned: it must not speak for the screen any more. */
  stale: () => boolean;
  dropped: () => void;
}

/** Installs the four handlers of one socket. */
function wire(socket: WebSocket, { callbacks, report, stale, dropped }: SocketWiring): void {
  socket.onopen = () => {
    if (stale()) return;
    report({ status: 'live', closeReason: null });
    callbacks.current.onOpen?.();
  };
  socket.onmessage = (event) => {
    const parsed = stale() ? undefined : parseFrame(event.data);
    if (parsed) callbacks.current.onFrame(parsed.frame);
  };
  socket.onclose = (event) => {
    if (stale() || isLeavingForAuth()) return;
    if (callbacks.current.onClose?.({ code: event.code, reason: event.reason }) !== 'stop') dropped();
    else report({ status: 'refused', closeReason: event.reason || null });
  };
  socket.onerror = () => socket.close();
}

/** Opens the socket and keeps it open until the returned cleanup runs. */
function runLiveSocket(path: string, callbacks: SocketWiring['callbacks'], report: SocketWiring['report']): () => void {
  let closed = false;
  let socket: WebSocket | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  const gone = () => closed || isLeavingForAuth();
  const dropped = async () => {
    if (await diagnoseDrop(gone) === 'handled') return;
    report({ status: 'down', closeReason: null });
    reconnectTimer = setTimeout(connect, RECONNECT_MS);
  };
  const connect = () => {
    if (gone()) return;
    const current = new WebSocket(socketUrl(path));
    socket = current;
    wire(current, { callbacks, report, stale: () => closed || current !== socket, dropped: () => void dropped() });
  };
  connect();
  return () => {
    closed = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    socket?.close();
  };
}

/**
 * The dashboard's one socket client (docs/superpowers/specs/2026-10-28-live-data-design.md §3.2). The callbacks are
 * read through a ref, so a new callback each render does not reopen the socket; only the path and `enabled` do.
 */
export function useLiveSocket(options: LiveSocketOptions): LiveSocketState {
  const callbacks = useRef(options);
  callbacks.current = options;
  const { path, enabled = true } = options;
  const [state, setState] = useState<LiveSocketState>(CONNECTING);
  useEffect(() => {
    if (!enabled) return;
    setState(CONNECTING);
    return runLiveSocket(path, callbacks, setState);
  }, [path, enabled]);
  return state;
}
