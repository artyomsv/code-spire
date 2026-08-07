import type { ChargeKind, ChargeLineView } from '../api';
import { formatEventTime } from '../format';
import { formatCost } from '../money';
import { TOKEN_TYPE_LABEL } from '../llmPricing';

const CALL_KIND_LABEL: Record<ChargeKind, string> = {
  REVIEW: 'Review',
  RECONCILE: 'Reconcile',
  FOLLOWUP: 'Follow-up',
};

/** Every charge line sharing one kind+pricedAt is one physical LLM call, split into its token
 *  dimensions — the ledger has no callRef on this view, so that pair is the grouping key. */
interface Call {
  key: string;
  kind: ChargeKind;
  model: string;
  pricedAt: string;
  lines: ChargeLineView[];
}

function groupByCall(lines: ChargeLineView[]): Call[] {
  const calls: Call[] = [];
  const byKey = new Map<string, Call>();
  for (const line of lines) {
    const key = `${line.kind}|${line.pricedAt}`;
    let call = byKey.get(key);
    if (!call) {
      call = { key, kind: line.kind, model: line.model, pricedAt: line.pricedAt, lines: [] };
      byKey.set(key, call);
      calls.push(call);
    }
    call.lines.push(line);
  }
  return calls;
}

/** The sum of what could be priced. A null line (UNKNOWN) contributes nothing to the sum without
 *  making the whole total null — a partly-priced set of lines still has a known partial figure. */
function knownCost(lines: ChargeLineView[]): number {
  return lines.reduce((sum, l) => sum + (l.costMillicents ?? 0), 0);
}

/** A call's headline cost: "self-hosted" rather than "$0.00" when every line asserts a zero, and
 *  flagged partial when any line in it could not be priced. */
function callCostText(lines: ChargeLineView[]): string {
  if (lines.every((l) => l.pricingMode === 'UNMETERED')) return 'self-hosted (unmetered)';
  const amount = formatCost(knownCost(lines));
  return lines.some((l) => l.pricingMode === 'UNKNOWN') ? `${amount} (partial)` : amount;
}

function lineDetail(line: ChargeLineView): string {
  if (line.pricingMode === 'UNMETERED') return 'self-hosted (unmetered)';
  if (line.pricingMode === 'UNKNOWN') return 'could not be priced';
  return `${formatCost(line.rateMillicentsPerMillion)}/1M tokens · ${formatCost(line.costMillicents)}`;
}

function lineRow(line: ChargeLineView, i: number) {
  return (
    <div key={i} className="usage-tokens">
      {TOKEN_TYPE_LABEL[line.tokenType]}: {line.tokens.toLocaleString()} tokens · {lineDetail(line)}
    </div>
  );
}

function callRow(call: Call) {
  return (
    <div key={call.key} className={`usage-call ${call.kind.toLowerCase()}`}>
      <div className="usage-call-top">
        <span className="usage-kind">{CALL_KIND_LABEL[call.kind] ?? call.kind}</span>
        {call.pricedAt && <span className="usage-time">{formatEventTime(call.pricedAt)}</span>}
        <span className="usage-cost">{callCostText(call.lines)}</span>
      </div>
      <div className="usage-call-meta">
        <div className="usage-model">{call.model}</div>
        {call.lines.map(lineRow)}
      </div>
    </div>
  );
}

/**
 * A review's per-call, per-token-type cost breakdown — a strict superset of the legacy single-row
 * `UsageView` it replaced (Task 2 removed the server's `usage` field entirely).
 *
 * <p>The total sums only what could be priced; when `unpricedCalls > 0` that total is explicitly
 * marked partial rather than presented as complete — a silently incomplete total is the same defect
 * the zero-vs-unknown distinction exists to remove, one layer up.
 */
export default function ReviewCostCard({
  lines,
  unpricedCalls,
}: {
  lines: ChargeLineView[];
  unpricedCalls: number;
}) {
  if (lines.length === 0) {
    return (
      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>Model usage</h3>
        </div>
        <div className="body">
          <div className="clean">
            <span className="em mono">—</span>No model calls recorded yet.
          </div>
        </div>
      </div>
    );
  }

  const calls = groupByCall(lines);
  const total = knownCost(lines);

  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Model usage</h3>
        <span className="badge">
          {calls.length} request{calls.length === 1 ? '' : 's'}
        </span>
      </div>
      <div className="body">
        <div className="usage-calls">
          {calls.map(callRow)}
          <div className="usage-total">
            <span>Total</span>
            <span className="accent">{formatCost(total)}</span>
          </div>
          {unpricedCalls > 0 && (
            <div className="usage-partial">
              {unpricedCalls} call{unpricedCalls === 1 ? '' : 's'} could not be priced — this total is
              partial.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
