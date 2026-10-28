import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getRun } from '../api';
import { isLeavingForAuth } from '../auth';
import { invalidateLive } from '../liveCache';
import { isRunEntry } from '../useLiveRuns';
import { useLiveSocket } from './useLiveSocket';

/** One run's detail, keyed on its id so a read for another run can never land here. */
export const runKey = (runId: string) => ['run', runId] as const;

/**
 * One run, read over REST and read again whenever the runs feed mentions it. A feed row is a list row and lacks the
 * detail's fields, so it is a notice to re-read rather than data to show.
 */
export function useRunDetail(runId: string) {
  const client = useQueryClient();
  const query = useQuery({ queryKey: runKey(runId), queryFn: () => getRun(runId) });

  useLiveSocket({
    path: '/api/ws/runs',
    onFrame: (frame) => {
      // A reconnect snapshot also refreshes an older run that is absent from the newest-200 list.
      if (Array.isArray(frame) || (isRunEntry(frame) && frame.runId === runId)) invalidateLive(client, runKey(runId));
    },
  });

  const error = query.error && !isLeavingForAuth() ? query.error.message || 'Failed to load run' : null;
  return { run: query.data ?? null, loading: query.data === undefined && error === null, error };
}
