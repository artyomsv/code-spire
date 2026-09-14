import WorkItemPolicy from './WorkItemPolicy';
import WorkItemJourney from './WorkItemJourney';
import WorkItemPreparation from './WorkItemPreparation';
import { useEffect, useState, useRef } from 'react';
import { Link, useParams } from 'react-router';
import { getWorkItem, getWorkItemTracker, resumeWorkItem, type WorkItemDetail as Detail, type WorkItemTracker } from '../../api';
import { useMe } from '../../hooks/useMe';
import { canAdminister } from '../../auth';
import { WorkflowStatus, workReason } from './WorkItems';

export default function WorkItemDetail() {
  const { id = '' } = useParams();
  const [refresh, setRefresh] = useState(0);
  const [state, setState] = useState<{ item: Detail | null; error: string | null }>({ item: null, error: null });
  const [tracker, setTracker] = useState<{ value: WorkItemTracker | null; error: string | null }>({ value: null, error: null });
  useEffect(() => {
    let active = true;
    setState({ item: null, error: null });
    setTracker({ value: null, error: null });
    getWorkItem(id).then(item => { if (active) setState({ item, error: null }); })
      .catch(error => { if (active) setState({ item: null, error: String(error) }); });
    getWorkItemTracker(id).then(value => { if (active) setTracker({ value, error: null }); })
      .catch(error => { if (active) setTracker({ value: null, error: String(error) }); });
    return () => { active = false; };
  }, [id, refresh]);
  const { item, error } = state;
  return <section className="content"><div className="card" style={{ padding: 18 }}>
    <Link to="/work-items">← Work items</Link>
    {error ? <p role="alert">{error}</p> : !item ? <p>Loading work item…</p> : <>
      <h2>{item.issueKey}</h2>
      <p><a href={item.trackerUrl} target="_blank" rel="noreferrer">Open ticket in tracker</a> · {item.repository}</p>
      {item.preparation ? <WorkItemJourney item={item} /> : <><h3>Workflow</h3><WorkflowStatus status={item.workflowStatus} />
      <p>{workReason(item.reason)}</p>
      <p>Phase: {item.phase} · Generation: {item.generation}</p></>}
      <WorkItemPolicy item={item} />
      {item.gate && <p>Approval: {item.gate.state} · <Link to="/approvals">Open approvals</Link></p>}
      <WorkItemActions key={`${item.id}:${item.revision}`} item={item} changed={() => setRefresh(value => value + 1)} />
      <WorkItemPreparation key={`preparation:${item.id}:${item.revision}`} item={item} changed={() => setRefresh(value => value + 1)} />
      <button className="btn" onClick={() => setRefresh(value => value + 1)}>Refresh workflow</button>
      <h3>Applied labels</h3>
      <ul>{item.appliedLabels.map(label => <li key={label.label}><strong>{label.label}</strong>: actor {label.actorId}, {label.origin.toLowerCase().split('_').join(' ')}; profile version {label.profileVersion}</li>)}</ul>
      <h3>Ignored labels</h3>
      {item.ignoredLabels.length === 0 ? <p>No labels were ignored.</p> : <ul>{item.ignoredLabels.map(label =>
        <li key={label.label}><strong>{label.label}</strong>: {workReason(label.reason)}</li>)}</ul>}
      <h3>Workflow history</h3>
      <ol>{item.events.map(event => <li key={event.sequence}>{event.type === 'WorkItemEvent' ? 'Workflow updated' : event.type}: {workReason(event.reason)}</li>)}</ol>
      <h3>Current tracker content</h3>
      {tracker.error ? <p role="alert">Tracker unavailable: {tracker.error}. The workflow above remains available.</p> :
        !tracker.value ? <p>Loading current tracker content…</p> : <>
          <h4>{tracker.value.title}</h4><p>Tracker status: {tracker.value.trackerStatus}</p>
          <p style={{ whiteSpace: 'pre-wrap' }}>{tracker.value.body}</p>
        </>}
    </>}
  </div></section>;
}

function WorkItemActions({ item, changed }: { item: Detail; changed: () => void }) {
  const { me } = useMe();
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  async function resume(readmit: boolean) {
    setBusy(true); setError('');
    try { await resumeWorkItem(item, readmit); if (active.current) changed(); }
    catch (failure) { if (active.current) setError(String(failure)); } finally { if (active.current) setBusy(false); }
  }
  if (!canAdminister(me)) return null;
  return <div>
    {['awaiting_input', 'capability_unavailable', 'suspended'].includes(item.workflowStatus) &&
      <button className="btn" disabled={busy} onClick={() => void resume(false)}>Recheck and resume</button>}
    {['not_eligible', 'stopped', 'failed', 'completed', 'awaiting_input', 'capability_unavailable'].includes(item.workflowStatus) &&
      <button className="btn" disabled={busy} onClick={() => void resume(true)}>Re-admit under current policy</button>}
    {error && <p role="alert">{error}</p>}
  </div>;
}
