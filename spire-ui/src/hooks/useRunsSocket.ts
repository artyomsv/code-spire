import { useEffect, useRef } from 'react';
import type { RunListEntry } from '../api';
import { fetchMe, goToFullLogin, isLeavingForAuth, needsLogin } from '../auth';

export type RunsFrame = RunListEntry[] | RunListEntry;

export function isRunEntry(value: unknown): value is RunListEntry {
  return typeof value === 'object' && value !== null
    && typeof (value as { runId?: unknown }).runId === 'string';
}

/** Shared session-aware transport for the list and detail; each owns its REST read model. */
export function useRunsSocket(onFrame: (frame: RunsFrame) => void) {
  const receive = useRef(onFrame);
  receive.current = onFrame;
  useEffect(() => {
    let closed = false;
    let ws: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    const connect = () => {
      if (closed || isLeavingForAuth()) return;
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const socket = new WebSocket(`${proto}//${location.host}/api/ws/runs`);
      ws = socket;
      socket.onmessage = (event) => {
        if (closed || socket !== ws) return;
        let data: unknown;
        try {
          data = JSON.parse(event.data);
        } catch {
          return;
        }
        if (Array.isArray(data)) receive.current(data.filter(isRunEntry));
        else if (isRunEntry(data)) receive.current(data);
      };
      socket.onclose = () => {
        if (closed || socket !== ws || isLeavingForAuth()) return;
        // Match the reviews lifecycle: a failed handshake gives no useful auth close code.
        // Diagnose the session before retrying, or cookie expiry becomes an endless reconnect loop.
        void fetchMe().then((me) => {
          if (closed || isLeavingForAuth()) return;
          if (needsLogin(me)) {
            goToFullLogin();
            return;
          }
          reconnectTimer = setTimeout(connect, 1500);
        });
      };
      socket.onerror = () => socket.close();
    };

    connect();
    return () => {
      closed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      ws?.close();
    };
  }, []);
}