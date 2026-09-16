import { Link } from 'react-router';
import { formatCost } from '../../money';
import { formatEventTime } from '../../format';
import JourneyStrip, { JourneyLegend } from './JourneyStrip';
import { nextAction, type WorkItemRow } from './workJourney';
import { workReason } from './workReasons';
import { WorkflowStatus } from './WorkItems';

interface Props {
  rows: WorkItemRow[];
  titles: ReadonlyMap<string, string>;
  /** While a read is in flight the rows stay, and the region says it is being refreshed. */
  busy: boolean;
}

/** What a row has cost so far. An unpriced run makes the total unknown, and the cell says so. */
function Cost({ row }: { row: WorkItemRow }) {
  if (!row.progress) return <>—</>;
  if (row.progress.usageUnknown) return <span className="chip warn" title="A run reported usage that has no price">unknown</span>;
  return <>{formatCost(row.progress.costMillicents ?? null)}</>;
}

/**
 * The triage table: which ticket, where it stands, why, and the one thing to do about it. The reason
 * is a sentence, not a status code, and the next action sits in the row that needs it.
 */
export default function WorkItemTable({ rows, titles, busy }: Props) {
  return <>
    <div className="prov-scroll" aria-busy={busy}>
      <table className="prov-table work-table">
        <thead><tr><th>Ticket</th><th>Where</th><th>Why · next</th><th>Profile</th><th className="cell-r">Cost</th><th className="cell-r">Updated</th></tr></thead>
        <tbody>{rows.map(row => {
          const action = nextAction(row);
          const title = titles.get(row.id);
          return <tr key={row.id}>
            <td>
              <Link className="work-title" to={`/work-items/${encodeURIComponent(row.id)}`}>{title ?? row.issueKey}</Link>
              <div className="prov-sub">{title ? `${row.issueKey} · ` : ''}{row.repository}</div>
            </td>
            <td><div className="work-where"><JourneyStrip item={row} /><span><WorkflowStatus status={row.workflowStatus} /> <span className="prov-sub">{row.phase}</span></span></div></td>
            <td>
              <div className="work-why">{workReason(row.reason)}</div>
              {action && <Link className="btn sm work-next" to={action.to}>{action.label}</Link>}
            </td>
            <td className="mono">{row.profile ? `${row.profile.name} v${row.profile.version}` : '—'}</td>
            <td className="cell-r"><Cost row={row} /></td>
            <td className="cell-r prov-sub">{formatEventTime(row.updatedAt)}</td>
          </tr>;
        })}</tbody>
      </table>
    </div>
    <JourneyLegend />
  </>;
}
