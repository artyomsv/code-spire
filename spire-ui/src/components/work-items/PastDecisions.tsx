import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { formatEventTime } from '../../format';
import SidePanel from '../SidePanel';
import * as approvalsApi from './approvalsApi';
import { workKeys } from './workQueries';

const STATE_TONE: Record<string, string> = { APPROVED: 'completed', REJECTED: 'failed', EXPIRED: 'cancelled', SUPERSEDED: 'superseded' };

/** Decisions that are no longer open, newest first: who answered, where, and when the question was asked. */
export default function PastDecisions({ onClose }: { onClose: () => void }) {
  const read = useQuery({ queryKey: workKeys.approvals(true), queryFn: () => approvalsApi.approvals(true) });
  const rows = read.data ?? null, error = read.error ? String(read.error) : '';
  return <SidePanel title="Past decisions" subtitle="The last 100 answered, expired or replaced decisions" busy={false} onClose={onClose}
    actions={<button className="btn-ghost" type="button" onClick={onClose}>Close</button>}>
    {error && <p className="prov-error" role="alert">{error}</p>}
    {!rows && !error && <p className="prov-note" role="status" aria-busy="true">Loading past decisions…</p>}
    {rows?.length === 0 && <p className="prov-note">No decision has been answered, expired or replaced yet.</p>}
    {rows && rows.length > 0 && <ul className="prov-list decision-history">{rows.map(row => <li key={`${row.gate.id}:${row.gate.version}`}>
      <span>
        <Link to={`/work-items/${encodeURIComponent(row.workItemId)}`}>{row.issueKey}</Link> · {row.gate.phase}
        <span className="prov-sub">{row.gate.resolver ? `by ${row.gate.resolver} via ${row.gate.channel}` : 'nobody answered'} · asked {formatEventTime(row.gate.openedAt)}</span>
      </span>
      <span className={`pill ${STATE_TONE[row.gate.state] ?? 'refused'}`}>{row.gate.state.toLowerCase()}</span>
    </li>)}</ul>}
  </SidePanel>;
}
