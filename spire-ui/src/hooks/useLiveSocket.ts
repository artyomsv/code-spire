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
    let closed = false;
    let socket: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    setState(CONNECTING);

    const connect = () => {
      if (closed || isLeavingForAuth()) return;
      const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const current = new WebSocket(`${scheme}//${location.host}${path}`);
      socket = current;
      // A socket this hook has replaced or abandoned must not speak for the screen any more.
      const stale = () => closed || current !== socket;
      current.onopen = () => {
        if (stale()) return;
        setState({ status: 'live', closeReason: null });
        callbacks.current.onOpen?.();
      };
      current.onmessage = (event) => {
        if (stale()) return;
        let frame: unknown;
        try {
          frame = JSON.parse(event.data);
        } catch {
          return; // a malformed frame must not blank a feed that was working
        }
        callbacks.current.onFrame(frame);
      };
      current.onclose = (event) => {
        if (stale() || isLeavingForAuth()) return;
        if (callbacks.current.onClose?.({ code: event.code, reason: event.reason }) === 'stop') {
          setState({ status: 'refused', closeReason: event.reason || null });
          return;
        }
        void afterDrop();
      };
      current.onerror = () => current.close();
    };

    // An expired or missing session closes a handshake with no useful code, so ask why before reacting: reconnecting
    // blindly hammered the identity provider on every expiry (useLiveReviews, before this hook).
    const afterDrop = async () => {
      const me = await fetchMe();
      if (closed || isLeavingForAuth()) return;
      if (needsLogin(me)) {
        goToFullLogin();
        return;
      }
      // Signed in to /api does not mean signed in to /gw: each service keeps its own session, and a handshake
      // cannot follow the redirect that says so (useAttention, before this hook).
      if (await ensureServiceSessions()) return;
      if (closed || isLeavingForAuth()) return;
      setState({ status: 'down', closeReason: null });
      reconnectTimer = setTimeout(connect, RECONNECT_MS);
    };

    connect();
    return () => {
      closed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, [path, enabled]);

  return state;
}
