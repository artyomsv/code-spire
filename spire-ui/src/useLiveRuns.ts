import { useEffect, useRef, useState } from 'react';
import { getRuns, type RunListEntry } from './api';
import { isLeavingForAuth } from './auth';
import { isRunEntry, useRunsSocket } from './hooks/useRunsSocket';

export const MAX_LIVE_RUNS = 200;
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

export function useLiveRuns() {
  const [runs, setRuns] = useState<RunListEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const wsDelivered = useRef(false);

  useEffect(() => {
    let closed = false;
    wsDelivered.current = false;
    // Same snapshot size as the socket, with no server-side filters. Once a socket frame lands,
    // this possibly older REST response must never overwrite it (useLiveReviews' race guard).
    getRuns({ limit: MAX_LIVE_RUNS }).then((rows) => {
      if (closed || wsDelivered.current) return;
      setRuns(sortRuns(rows.filter(isRunEntry)));
      setLoading(false);
    }).catch((failure: unknown) => {
      if (closed || wsDelivered.current || isLeavingForAuth()) return;
      setError(failure instanceof Error ? failure.message : 'Failed to load runs');
      setLoading(false);
    });

    return () => { closed = true; };
  }, []);

  useRunsSocket((data) => {
    if (Array.isArray(data)) setRuns(sortRuns(data));
    else setRuns((previous) => sortRuns(previous.some((row) => row.runId === data.runId)
      ? previous.map((row) => row.runId === data.runId ? data : row)
      : [...previous, data]));
    wsDelivered.current = true;
    setLoading(false);
    setError(null);
  });

  return { runs, loading, error };
}
