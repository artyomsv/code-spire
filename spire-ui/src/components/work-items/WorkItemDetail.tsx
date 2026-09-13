import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { getWorkItem, getWorkItemTracker, type WorkItemDetail as Detail, type WorkItemTracker } from '../../api';
import { WorkflowStatus, workReason } from './WorkItems';

export default function WorkItemDetail() {
  const { id = '' } = useParams();
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
  }, [id]);
  const { item, error } = state;
  return <section className="content"><div className="card" style={{ padding: 18 }}>
    <Link to="/work-items">← Work items</Link>
    {error ? <p role="alert">{error}</p> : !item ? <p>Loading work item…</p> : <>
      <h2>{item.issueKey}</h2>
      <p><a href={item.trackerUrl} target="_blank" rel="noreferrer">Open ticket in tracker</a> · {item.repository}</p>
      <h3>Workflow</h3><WorkflowStatus status={item.workflowStatus} />
      <p>{workReason(item.reason)}</p>
      <p>Phase: {item.phase} · Generation: {item.generation}</p>
      <p>{item.profile ? `Selected profile: ${item.profile.name} v${item.profile.version}` : 'No profile selected'}</p>
      <p>{item.ceiling ? `Repository ceiling: ${item.ceiling.name} v${item.ceiling.version}` : 'No repository ceiling configured'}</p>
      <p>{workReason(item.policyReason)}</p>
      <table><thead><tr><th>Phase</th><th>Effective mode</th><th>Mode at admission</th></tr></thead>
        <tbody>{Object.entries(item.effectiveModes).map(([phase, mode]) =>
          <tr key={phase}><th>{phase.toLowerCase()}</th><td>{mode}</td><td>{item.admittedModes[phase]}</td></tr>)}</tbody></table>
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
