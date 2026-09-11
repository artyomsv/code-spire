import { Clock3 } from 'lucide-react';
import type { RunView } from '../api';
import { formatEventTime } from '../format';

export default function RunPhases({ run }: { run: RunView }) {
  const phases = [['Queued', run.startedAt], ['Agent running', run.agentStartedAt], ['Ended', run.endedAt]];
  return <ol className="run-phases" aria-label="Run phases">{phases.map(([label, at]) => (
    <li key={label}>
      <span className="run-phase-label"><Clock3 size={15} aria-hidden="true" />{label}</span>
      {at ? <time dateTime={at} title={at}>{formatEventTime(at)}</time> : <span className="prov-sub">not recorded</span>}
    </li>
  ))}</ol>;
}
