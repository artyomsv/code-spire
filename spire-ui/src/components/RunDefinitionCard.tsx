import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { RunView } from '../api';
import { formatEventTime } from '../format';
import RunCard, { RunFields } from './RunCard';
import { isRunUnfinished, reviewPath, runDuration } from './Runs';

export default function RunDefinitionCard({ run }: { run: RunView }) {
  const [now, setNow] = useState(Date.now);
  const ticking = isRunUnfinished(run.status) && !run.endedAt;
  useEffect(() => {
    if (!ticking) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [ticking]);
  const link = run.reviewId ? reviewPath(run.reviewId) : null;
  const elapsed = runDuration({ ...run, endedAt: run.endedAt ?? (ticking ? new Date(now).toISOString() : null) });
  return <RunCard title="Run definition"><RunFields rows={[
    ['Kind', run.kind], ['Harness', run.harness], ['Model', run.model], ['Attempt', run.attempt],
    ['Review', link ? <Link to={link}>{run.reviewId}</Link> : run.reviewId],
    ['Queued at', run.startedAt ? formatEventTime(run.startedAt) : null], ['Elapsed', elapsed],
  ]} /></RunCard>;
}
