import type { RunView } from '../api';
import { formatCost } from '../money';
import RunCard, { RunFields } from './RunCard';
import { isChargedUnderItsReview } from './Runs';

const TOKENS = [['INPUT', 'Input'], ['CACHED_INPUT', 'Cached input'], ['CACHE_WRITE', 'Cache write'],
  ['OUTPUT', 'Output'], ['REASONING', 'Reasoning'], ['TOTAL', 'Unsplit total']] as const;

export default function RunSpendCard({ run }: { run: RunView }) {
  return <RunCard title="Spend">
    {/* A review run's call is charged once, under its review: showing it here too would count it twice. */}
    {isChargedUnderItsReview(run.kind)
      ? <p className="prov-sub">Charged under its review</p>
      : <div className="run-cost" aria-label="Total cost">{formatCost(run.cost.millicents)}</div>}
    {!isChargedUnderItsReview(run.kind) && run.spend.unpricedLines > 0 && <p className="prov-sub">
      {formatCost(run.spend.priced)} priced · {run.spend.unpricedLines} unpriced charge lines
    </p>}
    <RunFields rows={TOKENS.filter(([key]) => key !== 'TOTAL' || key in run.spend.tokensByType)
      .map(([key, label]) => [label, run.spend.tokensByType[key]?.toLocaleString() ?? '—'])} />
  </RunCard>;
}
