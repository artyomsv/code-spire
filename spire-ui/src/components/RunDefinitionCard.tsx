import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { RunView } from '../api';
import { formatEventTime } from '../format';
import RunCard, { RunFields } from './RunCard';
import { isRunUnfinished, reviewPath, runDuration } from './Runs';

// Which credential paid, in the operator's words. A subscription seat is not billed per token, so it
// must never read as an API key: that is the one question this row exists to answer (M3.5 part F).
function billedTo(run: RunView): string | null {
  if (!run.credentialLabel) return null;
  const type = run.credentialType ? ` · ${run.credentialType}` : '';
  if (run.credentialAuthMode === 'SUBSCRIPTION') return `Subscription ${run.credentialLabel}${type}, no per-token price`;
  return `API key ${run.credentialLabel}${type ? `${type}, billed per token` : ''}`;
}

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
  const billing = billedTo(run);
  return <RunCard title="Run definition"><RunFields rows={[
    ['Kind', run.kind], ['Harness', run.harness], ['Model', run.model], ['Billed to', billing], ['Attempt', run.attempt],
    ['Review', link ? <Link to={link}>{run.reviewId}</Link> : run.reviewId],
    ['Queued at', run.startedAt ? formatEventTime(run.startedAt) : null], ['Elapsed', elapsed],
  ]} /></RunCard>;
}
