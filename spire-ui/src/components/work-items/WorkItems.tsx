import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { getWorkItems, type WorkItemPage } from '../../api';

const STATES = new Map(Object.entries({
  not_eligible: { label: 'Not eligible', tone: 'refused' },
  awaiting_input: { label: 'Awaiting input', tone: 'refused' },
  capability_unavailable: { label: 'Capability unavailable', tone: 'refused' },
  active: { label: 'Active', tone: 'reviewing' },
  waiting_approval: { label: 'Waiting for approval', tone: 'refused' },
  stopped: { label: 'Stopped', tone: 'cancelled' },
  suspended: { label: 'Suspended', tone: 'refused' },
  retired: { label: 'Retired', tone: 'cancelled' },
  completed: { label: 'Completed', tone: 'completed' },
  failed: { label: 'Failed', tone: 'failed' },
}));

export function WorkflowStatus({ status }: { status: string }) {
  const state = STATES.get(status) ?? { label: `Unknown (${status})`, tone: 'refused' };
  return <span className={`pill ${state.tone}`}>{state.label}</span>;
}

const REASONS = new Map(Object.entries({
  specification_required: 'A specification is required before work can continue.',
  actor_not_allowed: 'The person who applied this label is not on the source allowlist.',
  label_unattributed: 'The current label applier could not be confirmed.',
  actor_id_missing: 'The tracker did not identify the person who applied this label.',
  no_eligible_label: 'No current label is eligible to select a profile.',
  ceiling_missing: 'Configure a repository policy ceiling before admitting work.',
  policy_selected: 'The current labels selected this profile.',
  policy_clamped: 'The effective policy is restricted by another label, the admission version, or the ceiling.',
  intake_off: 'The effective policy does not allow intake.',
  spec_off: 'The effective policy stops before specification.',
  intake_approval_unavailable: 'Intake needs approval, but this approval capability is not available yet.',
  spec_approval_unavailable: 'Specification needs approval, but this approval capability is not available yet.',
}));

export function workReason(reason: string) { return REASONS.get(reason) ?? reason; }

export default function WorkItems() {
  const [offset, setOffset] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [state, setState] = useState<{ page: WorkItemPage | null; error: string | null }>({ page: null, error: null });
  useEffect(() => {
    let active = true;
    setState({ page: null, error: null });
    getWorkItems(offset, 50).then(page => { if (active) setState({ page, error: null }); })
      .catch(error => { if (active) setState({ page: null, error: String(error) }); });
    return () => { active = false; };
  }, [offset, refresh]);
  const { page, error } = state;
  return <section className="content"><div className="card" style={{ padding: 18 }}>
    <div className="prov-head"><h2 className="prov-title">Work items</h2>
      <button className="btn" onClick={() => setRefresh(value => value + 1)}>Refresh work items</button></div>
    {error ? <p role="alert">{error}</p> : !page ? <p>Loading work items…</p> : <>
      {page.items.length === 0 ? <p>No work items yet. Registered sources admit tickets after a scan or label event.</p> :
        <table className="prov-table"><thead><tr><th>Ticket</th><th>Repository</th><th>Workflow</th><th>Phase</th><th>Profile</th></tr></thead>
          <tbody>{page.items.map(item => <tr key={item.id}>
            <td><Link to={`/work-items/${encodeURIComponent(item.id)}`}>{item.issueKey}</Link></td>
            <td>{item.repository}</td><td><WorkflowStatus status={item.workflowStatus} /></td><td>{item.phase}</td>
            <td>{item.profile ? `${item.profile.name} v${item.profile.version}` : 'No profile selected'}</td>
          </tr>)}</tbody></table>}
      <div className="prov-actions" style={{ marginTop: 16 }}>
        <button className="btn" disabled={page.offset === 0} onClick={() => setOffset(Math.max(0, page.offset - page.limit))}>Previous page</button>
        <span>{page.total === 0 ? '0 items' : `${page.offset + 1}–${page.offset + page.items.length} of ${page.total}`}</span>
        <button className="btn" disabled={page.offset + page.items.length >= page.total} onClick={() => setOffset(page.offset + page.limit)}>Next page</button>
      </div>
    </>}
  </div></section>;
}
