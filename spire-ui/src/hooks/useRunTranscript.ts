import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getRunTranscript, type RunEvent } from '../api';
import { isLeavingForAuth } from '../auth';
import { mergeLive } from '../liveCache';
import { useLiveSocket } from './useLiveSocket';

const MAX_EVENTS = 2000;
type Transcript = { events: RunEvent[]; dropped: boolean };
const EMPTY: Transcript = { events: [], dropped: false };

/** One run's transcript: the REST page and the live tail merge into the same entry. */
export const transcriptKey = (runId: string) => ['run-transcript', runId] as const;

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

/**
 * One run's transcript. Events are immutable, so the REST page and the live tail are merged, never replaced: the
 * REST read merges with what the socket already delivered when it lands, and each frame merges into the entry.
 * A policy close (1008, e.g. "no such run") is final: it shows the reason, never reopens, and drops the REST read.
 */
export function useRunTranscript(runId: string) {
  const client = useQueryClient();
  const key = transcriptKey(runId);
  const own = (values: unknown[]) => values.filter(isEvent).filter((event) => event.runId === runId);
  const query = useQuery({
    queryKey: key,
    queryFn: async () => merge(client.getQueryData<Transcript>(key) ?? EMPTY, own(await getRunTranscript(runId))),
  });
  const socket = useLiveSocket({
    path: `/api/ws/runs/transcript?runId=${encodeURIComponent(runId)}`,
    onFrame: (frame) => {
      if (Array.isArray(frame)) mergeLive<Transcript>(client, key, (current) => merge(current ?? EMPTY, own(frame)));
    },
    onClose: ({ code }) => {
      if (code !== 1008) return 'reconnect';
      void client.cancelQueries({ queryKey: key, exact: true });
      return 'stop';
    },
  });

  const transcript = query.data ?? EMPTY;
  if (socket.status === 'refused') {
    return { ...transcript, loading: false, error: socket.closeReason ?? 'Transcript access refused' };
  }
  // A live tail on screen is not captioned with an older REST page's failure, nor with one caused by leaving.
  const error = query.error && query.data === undefined && !isLeavingForAuth()
    ? query.error.message || 'Failed to load transcript' : null;
  return { ...transcript, loading: query.data === undefined && error === null, error };
}
