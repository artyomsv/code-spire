import WorkItemPolicy from './WorkItemPolicy';
import WorkItemJourney from './WorkItemJourney';
import WorkItemPreparation from './WorkItemPreparation';
import { useEffect, useState, useRef } from 'react';
import { Link, useParams } from 'react-router';
import { getWorkItem, getWorkItemTracker, resumeWorkItem, type WorkItemDetail as Detail, type WorkItemTracker } from '../../api';
import { useMe } from '../../hooks/useMe';
import { canAdminister } from '../../auth';
import { WorkflowStatus, workReason } from './WorkItems';
import { actorLabel } from '../actorsApi';

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
    {error ? <p role="alert">{error}</p> : !item ? <p role="status" aria-busy="true">Loading work item…</p> : <>
      {/* The ticket title names the work; the key alone is a bare number on GitHub and sends the
          reader to the tracker to find out what this item is. The title comes from the separate
          tracker read, which may fail on its own, so the key remains the heading until it lands. */}
      <h2>{tracker.value ? tracker.value.title : item.issueKey}</h2>
      <p className="prov-sub">{item.issueKey} · {item.repository}</p>
      <p><a href={item.trackerUrl} target="_blank" rel="noreferrer">Open ticket in tracker</a></p>
      {item.preparation ? <WorkItemJourney item={item} /> : <><h3>Workflow</h3><WorkflowStatus status={item.workflowStatus} />
      <p>{workReason(item.reason)}</p>
      <p>Phase: {item.phase} · Generation: {item.generation}</p></>}
      <WorkItemPolicy item={item} />
      {item.gate && <p>Approval: {item.gate.state} · <Link to="/approvals">Open approvals</Link></p>}
      <WorkItemActions key={`${item.id}:${item.revision}`} item={item} changed={() => setRefresh(value => value + 1)} />
      <WorkItemPreparation key={`preparation:${item.id}:${item.revision}`} item={item} changed={() => setRefresh(value => value + 1)} />
      <button className="btn" onClick={() => setRefresh(value => value + 1)}>Refresh workflow</button>
      <h3>Applied labels</h3>
      {/* The label stores a stable provider id; the handle is looked up for reading and never stored
          here, so a rename shows the current handle on the next read. */}
      <ul>{item.appliedLabels.map(label => {
        const person = item.people?.find(candidate => candidate.providerUserId === label.actorId);
        return <li key={label.label}><strong>{label.label}</strong>: added by {person ? actorLabel(person) : 'an unknown person'}
          {' '}<span className="prov-sub">{label.actorId}</span>, {label.origin.toLowerCase().split('_').join(' ')}; profile version {label.profileVersion}</li>;
      })}</ul>
      <h3>Ignored labels</h3>
      {item.ignoredLabels.length === 0 ? <p>No labels were ignored.</p> : <ul>{item.ignoredLabels.map(label =>
        <li key={label.label}><strong>{label.label}</strong>: {workReason(label.reason)}</li>)}</ul>}
      <h3>Workflow history</h3>
      {item.control?.note && <p>Operator: {item.control.operator ?? 'Unknown actor'} · {item.control.note}</p>}
      {item.control?.observedHead && <p className="mono">Observed head: {item.control.observedHead}</p>}
      <ol>{item.events.map(event => <li key={event.sequence}>{event.type === 'WorkItemEvent' ? 'Workflow updated' : event.type}: {workReason(event.reason)}</li>)}</ol>
      <h3>Current tracker content</h3>
      {tracker.error ? <p role="alert">Tracker unavailable: {tracker.error}. The workflow above remains available.</p> :
        !tracker.value ? <p role="status" aria-busy="true">Loading current tracker content…</p> : <>
          {/* The title already heads the page; repeating it here only competes with it. */}
          <p>Tracker status: {tracker.value.trackerStatus}</p>
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
  const [note, setNote] = useState('');
  async function resume(readmit: boolean) {
    setBusy(true); setError('');
    try {
      if (item.workflowStatus === 'suspended') await resumeWorkItem(item, readmit, note);
      else await resumeWorkItem(item, readmit);
      if (active.current) changed();
    }
    catch (failure) { if (active.current) setError(String(failure)); } finally { if (active.current) setBusy(false); }
  }
  if (!canAdminister(me)) return null;
  return <div>
    {item.workflowStatus === 'suspended' && <label className="field">Resume note<textarea disabled={busy} value={note} onChange={event => setNote(event.target.value)} /></label>}
    {['awaiting_input', 'capability_unavailable', 'suspended'].includes(item.workflowStatus) &&
      <button className="btn" disabled={busy || item.workflowStatus === 'suspended' && !note.trim()} onClick={() => void resume(false)}>
        {busy ? 'Rechecking…' : 'Recheck and resume'}</button>}
    {['not_eligible', 'stopped', 'failed', 'completed', 'awaiting_input', 'capability_unavailable'].includes(item.workflowStatus) &&
      <button className="btn" disabled={busy} onClick={() => void resume(true)}>
        {busy ? 'Re-admitting…' : 'Re-admit under current policy'}</button>}
    {busy && <p role="status">Working. The buttons unlock when the server answers.</p>}
    {error && <p role="alert">{error}</p>}
  </div>;
}
