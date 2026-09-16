import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { getWorkItem, getWorkItemTracker, type WorkItemDetail as Detail, type WorkItemTracker } from '../../api';
import { formatEventTime } from '../../format';
import SidePanel from '../SidePanel';
import DecisionPanel from './DecisionPanel';
import JourneyStrip from './JourneyStrip';
import WorkItemActions from './WorkItemActions';
import WorkItemPolicy from './WorkItemPolicy';
import WorkItemPreparation from './WorkItemPreparation';
import WorkItemSteps from './WorkItemSteps';
import { nextAction } from './workJourney';
import { workReason } from './workReasons';
import { WorkflowStatus } from './WorkItems';

/**
 * One work item as its journey. The heading names the ticket, the steps say where it is and what a
 * person can do there, and everything else — policy, ticket text, history — folds underneath.
 */
export default function WorkItemDetail() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const [refresh, setRefresh] = useState(0);
  // What the last action reported. It survives the re-read that action triggers, and a new action
  // clears it, so a rule from an earlier recheck never sits beside a later, different outcome.
  const [notice, setNotice] = useState('');
  const [state, setState] = useState<{ item: Detail | null; error: string | null }>({ item: null, error: null });
  const [tracker, setTracker] = useState<{ value: WorkItemTracker | null; error: string | null }>({ value: null, error: null });
  useEffect(() => { setNotice(''); }, [id]);
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

  function reread(message = '') { setNotice(message); setRefresh(value => value + 1); }
  function open(panel: 'decide' | 'prepare') { setNotice(''); setParams({ [panel]: '1' }); }
  function close() { setParams({}); }
  const { item, error } = state;
  const action = item ? nextAction(item) : null;

  return <section className="content"><div className="card work-detail">
    <Link to="/work-items">← Work items</Link>
    {error ? <p className="prov-error" role="alert">{error}</p> : !item ? <p className="prov-note" role="status" aria-busy="true">Loading work item…</p> : <>
      {/* The ticket title names the work; the key alone is a bare number on GitHub. The title comes
          from the separate tracker read, which may fail on its own, so the key heads until it lands. */}
      <div className="work-head">
        <div className="grow">
          <h2>{tracker.value ? tracker.value.title : item.issueKey}</h2>
          <p className="prov-sub">{item.issueKey} · {item.repository}</p>
        </div>
        <div className="prov-actions">
          <a className="btn-ghost sm" href={item.trackerUrl} target="_blank" rel="noreferrer">Open ticket in tracker</a>
          <button className="btn-ghost sm" type="button" onClick={() => reread()}>Refresh workflow</button>
        </div>
      </div>
      <div className="work-status">
        <WorkflowStatus status={item.workflowStatus} /><JourneyStrip item={item} />
        <span className="prov-sub">{item.profile ? `${item.profile.name} v${item.profile.version}` : 'No profile'} · updated {formatEventTime(item.updatedAt)}</span>
      </div>
      {notice && <p className="prov-note work-notice" role="status">{notice}</p>}
      <WorkItemSteps item={item} current={<>
        {action?.label === 'Add missing prices' && <Link className="btn sm" to={action.to}>Add missing prices</Link>}
        {action?.label === 'Prepare the task' && <button className="btn sm" type="button" onClick={() => open('prepare')}>Prepare the task</button>}
        {item.gate?.state === 'OPEN' && <button className="btn sm" type="button" onClick={() => open('decide')}>Review the {item.gate.phase} decision</button>}
        <WorkItemActions key={`${item.id}:${item.revision}`} item={item} started={() => setNotice('')} changed={reread} />
      </>} />
      <details className="work-more"><summary>Policy and limits</summary><WorkItemPolicy item={item} /></details>
      <details className="work-more" open={tracker.error !== null}><summary>Ticket</summary>
        {tracker.error ? <p className="prov-error" role="alert">Tracker unavailable: {tracker.error}. The workflow above remains available.</p> :
          !tracker.value ? <p className="prov-note" role="status" aria-busy="true">Loading current tracker content…</p> : <>
            <p className="factory-note">Tracker status: {tracker.value.trackerStatus}</p>
            <p className="work-body">{tracker.value.body}</p>
          </>}
      </details>
      <details className="work-more"><summary>Full history</summary>
        {item.control?.note && <p className="factory-note">Operator: {item.control.operator ?? 'Unknown actor'} · {item.control.note}</p>}
        {item.control?.observedHead && <p className="factory-note mono">Observed head: {item.control.observedHead}</p>}
        <ol className="work-history">{item.events.map(event => <li key={event.sequence}>
          <time className="prov-sub">{formatEventTime(event.occurredAt)}</time>
          <span>{event.type === 'WorkItemEvent' ? 'Workflow updated' : event.type}: {workReason(event.reason)}</span>
        </li>)}</ol>
      </details>
      {params.get('decide') && <DecisionPanel itemId={item.id} title={tracker.value?.title ?? null} onClose={close}
        onDecided={message => { close(); reread(message); }} />}
      {params.get('prepare') && <SidePanel title="Prepare the task" subtitle={`${item.issueKey} · ${item.repository}`} busy={false} onClose={close}
        actions={<button className="btn-ghost" type="button" onClick={close}>Close</button>}>
        <WorkItemPreparation key={`preparation:${item.id}:${item.revision}`} item={item} changed={() => { close(); reread('The prepared task was registered.'); }} />
      </SidePanel>}
    </>}
  </div></section>;
}
