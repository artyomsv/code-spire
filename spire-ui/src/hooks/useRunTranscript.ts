import { useEffect, useState } from 'react';
import { getRunTranscript, type RunEvent } from '../api';
import { fetchMe, goToFullLogin, isLeavingForAuth, needsLogin } from '../auth';

const MAX_EVENTS = 2000;
type Transcript = { events: RunEvent[]; dropped: boolean };

function isEvent(value: unknown): value is RunEvent {
  if (typeof value !== 'object' || value === null) return false;
  const event = value as Partial<RunEvent>;
  return typeof event.runId === 'string' && Number.isSafeInteger(event.sequence)
    && typeof event.at === 'string' && typeof event.kind === 'string'
    && typeof event.text === 'string' && typeof event.error === 'boolean';
}

function merge(current: Transcript, incoming: RunEvent[]): Transcript {
  const bySequence = new Map(current.events.map((event) => [event.sequence, event]));
  for (const event of incoming) {
    if (!bySequence.has(event.sequence)) bySequence.set(event.sequence, event);
  }
  const ordered = [...bySequence.values()].sort((a, b) => a.sequence - b.sequence);
  return { events: ordered.slice(-MAX_EVENTS), dropped: current.dropped || ordered.length > MAX_EVENTS };
}

export function useRunTranscript(runId: string) {
  const [transcript, setTranscript] = useState<Transcript>({ events: [], dropped: false });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let closed = false;
    let terminal = false;
    let live = false;
    let ws: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    setTranscript({ events: [], dropped: false });
    setLoading(true);
    setError(null);
    const accept = (events: RunEvent[]) => {
      setTranscript((current) => merge(current, events.filter((event) => event.runId === runId)));
      setLoading(false);
      setError(null);
    };
    void getRunTranscript(runId).then((events) => {
      // Events are immutable: merge a late REST page without overwriting a live tail.
      if (!closed && !terminal) accept(events.filter(isEvent));
    }).catch((failure: unknown) => {
      if (closed || terminal || live || isLeavingForAuth()) return;
      setError(failure instanceof Error ? failure.message : 'Failed to load transcript');
      setLoading(false);
    });

    const connect = () => {
      if (closed || terminal || isLeavingForAuth()) return;
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const socket = new WebSocket(`${proto}//${location.host}/api/ws/runs/transcript?runId=${encodeURIComponent(runId)}`);
      ws = socket;
      socket.onmessage = (event) => {
        if (closed || terminal || socket !== ws) return;
        let data: unknown;
        try { data = JSON.parse(event.data); } catch { return; }
        if (!Array.isArray(data)) return;
        live = true;
        accept(data.filter(isEvent));
      };
      socket.onclose = (event) => {
        if (closed || socket !== ws || isLeavingForAuth()) return;
        if (event.code === 1008) {
          terminal = true;
          setLoading(false);
          setError(event.reason || 'Transcript access refused');
          return;
        }
        void fetchMe().then((me) => {
          if (closed || terminal || isLeavingForAuth()) return;
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
  }, [runId]);

  return { ...transcript, loading, error };
}
