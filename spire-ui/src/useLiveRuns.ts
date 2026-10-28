import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getRuns, type RunListEntry } from './api';
import { isLeavingForAuth } from './auth';
import { useLiveSocket } from './hooks/useLiveSocket';
import { updateLive, upsertBy, writeLive } from './liveCache';

export const MAX_LIVE_RUNS = 200;
/** The runs list: the REST snapshot and the `/api/ws/runs` feed write the same entry. */
export const RUNS_KEY = ['runs', 'live'] as const;

export function isRunEntry(value: unknown): value is RunListEntry {
  return typeof value === 'object' && value !== null
    && typeof (value as { runId?: unknown }).runId === 'string';
}

/** PostgreSQL retains microseconds; Date.parse alone would tie different queue times. */
function queuedTime(value: string | null): bigint {
  const milliseconds = Date.parse(value ?? '');
  if (!Number.isFinite(milliseconds)) return 0n;
  const fraction = (/\.(\d+)/.exec(value ?? '')?.[1] ?? '').padEnd(9, '0').slice(3, 9);
  return BigInt(milliseconds) * 1_000_000n + BigInt(fraction);
}

function sortRuns(rows: RunListEntry[]): RunListEntry[] {
  return [...rows].sort((a, b) => {
    const timeA = queuedTime(a.startedAt);
    const timeB = queuedTime(b.startedAt);
    if (timeA !== timeB) return timeA > timeB ? -1 : 1;
    return a.runId === b.runId ? 0 : a.runId > b.runId ? -1 : 1;
  }).slice(0, MAX_LIVE_RUNS);
}

/**
 * The newest runs, kept live. The socket's snapshot frame replaces the list and a row frame updates one run; either
 * cancels a REST snapshot still out, so that older answer never overwrites them (liveCache.ts).
 */
export function useLiveRuns() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: RUNS_KEY,
    // Same snapshot size as the socket, with no server-side filters.
    queryFn: async () => sortRuns((await getRuns({ limit: MAX_LIVE_RUNS })).filter(isRunEntry)),
  });

  useLiveSocket({
    path: '/api/ws/runs',
    onFrame: (frame) => {
      if (Array.isArray(frame)) writeLive(client, RUNS_KEY, sortRuns(frame.filter(isRunEntry)));
      else if (isRunEntry(frame)) {
        updateLive<RunListEntry[]>(client, RUNS_KEY, previous => sortRuns(upsertBy(previous ?? [], frame, row => row.runId)));
      }
    },
  });

  // A failure while the window leaves for a login or a logout is that departure, not something to report.
  const error = query.error && !isLeavingForAuth() ? query.error.message || 'Failed to load runs' : null;
  return { runs: query.data ?? [], loading: query.data === undefined && error === null, error };
}
