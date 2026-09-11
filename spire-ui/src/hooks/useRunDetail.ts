import { useCallback, useEffect, useRef, useState } from 'react';
import { getRun, type RunView } from '../api';
import { isLeavingForAuth } from '../auth';
import { useRunsSocket } from './useRunsSocket';

export function useRunDetail(runId: string) {
  const [run, setRun] = useState<RunView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const sequence = useRef(0);
  const active = useRef(false);

  const refresh = useCallback(() => {
    const request = ++sequence.current;
    void getRun(runId).then((value) => {
      if (!active.current || request !== sequence.current) return;
      setRun(value);
      setError(null);
      setLoading(false);
    }).catch((failure: unknown) => {
      if (!active.current || request !== sequence.current || isLeavingForAuth()) return;
      setError(failure instanceof Error ? failure.message : 'Failed to load run');
      setLoading(false);
    });
  }, [runId]);

  useEffect(() => {
    active.current = true;
    setRun(null);
    setLoading(true);
    setError(null);
    refresh();
    return () => { active.current = false; ++sequence.current; };
  }, [refresh]);

  useRunsSocket((frame) => {
    // Reconnect snapshots also refresh older runs absent from the newest-200 list.
    if (Array.isArray(frame) || frame.runId === runId) refresh();
  });

  return { run, loading, error };
}
