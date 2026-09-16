import { useEffect, useRef, useState } from 'react';
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
  // Every action takes a number. A recheck that answers after a newer action started must not bring
  // its notice back, nor re-read the page under a panel the operator has since opened.
  const action = useRef(0);
  // The item the page last showed, so a re-read of it keeps its tracker text while a new item starts blank.
  const shown = useRef('');
  const panel = params.get('decide') ? 'decide' : params.get('prepare') ? 'prepare' : null;
  useEffect(() => { setNotice(''); }, [id]);
  useEffect(() => { if (panel) { action.current++; setNotice(''); } }, [panel]);
  useEffect(() => {
    let active = true;
    // A re-read of the same item keeps it on screen: blanking it would unmount an open panel and
    // hide the notice the re-read was started for.
    setState(previous => previous.item?.id === id ? previous : { item: null, error: null });
    setTracker(previous => shown.current === id ? previous : { value: null, error: null });
    getWorkItem(id).then(item => { if (active) { shown.current = id; setState({ item, error: null }); } })
      .catch(error => { if (active) setState(previous => ({ item: previous.item?.id === id ? previous.item : null, error: String(error) })); });
    getWorkItemTracker(id).then(value => { if (active) setTracker({ value, error: null }); })
      .catch(error => { if (active) setTracker({ value: null, error: String(error) }); });
    return () => { active = false; };
  }, [id, refresh]);

  function reread(message = '') { setNotice(message); setRefresh(value => value + 1); }
  function start() { setNotice(''); return ++action.current; }
  function open(name: 'decide' | 'prepare') { setParams({ [name]: '1' }); }
  function close() { setParams({}); }
  // The item on screen must be the one the address names; until its own read lands, show none. The
  // gap is the one render before the read effect clears the old item, which a test inside act() never
  // sees because act() flushes that effect first, so this guard is reasoned rather than tested.
  const item = state.item?.id === id ? state.item : null;
  const { error } = state;
  const next = item ? nextAction(item) : null;

  return <section className="content"><div className="card work-detail">
    <Link to="/work-items">← Work items</Link>
    {error && <p className="prov-error" role="alert">{error}</p>}
    {!item ? !error && <p className="prov-note" role="status" aria-busy="true">Loading work item…</p> : <>
      {/* The ticket title names the work; the key alone is a bare number on GitHub. The title comes
          from the separate tracker read, which may fail on its own, so the key heads until it lands. */}
      <div className="work-head">
        <div className="grow">
          <h2>{tracker.value ? tracker.value.title : item.issueKey}</h2>
          <p className="prov-sub">{item.issueKey} · {item.repository}</p>
        </div>
        <div className="prov-actions">
          <a className="btn-ghost sm" href={item.trackerUrl} target="_blank" rel="noreferrer">Open ticket in tracker</a>
          <button className="btn-ghost sm" type="button" onClick={() => { start(); reread(); }}>Refresh workflow</button>
        </div>
      </div>
      {item.preparationHealth && <p className="prov-error" role="status">
        The factory could not prepare this task: {workReason(item.preparationHealth.reason)}
        {item.preparationHealth.attempts > 1 ? ` (tried ${item.preparationHealth.attempts} times)` : ''}
      </p>}
{/* The prepared specification is a snapshot. Saying when the ticket has moved on is the whole
          reason an operator would press "Prepare again" — and never changes the approved bytes.
          A ticket that can no longer compose counts as changed too: emptying or over-filling it used to
          remove the notice rather than raise it, which is the drift that matters most. */}
      {item.preparation?.specification.origin === 'STORED' && tracker.value
        && (tracker.value.composedSha256
          ? tracker.value.composedSha256 !== item.preparation.specification.sha256
          : Boolean(tracker.value.composedRefusal))
        && <p className="factory-note" role="status">The ticket changed after it was prepared. What is approved and built is still the
          text that was prepared; prepare it again to use the edit.
          {tracker.value.composedRefusal && <> As it stands now it could not be prepared at all: {workReason(tracker.value.composedRefusal)}</>}</p>}
      <div className="work-status">
        <WorkflowStatus status={item.workflowStatus} /><JourneyStrip item={item} />
        <span className="prov-sub">{item.profile ? `${item.profile.name} v${item.profile.version}` : 'No profile'} · updated {formatEventTime(item.updatedAt)}</span>
      </div>
      {notice && <p className="prov-note work-notice" role="status">{notice}</p>}
      <WorkItemSteps item={item} current={<>
        {item.reason === 'run_usage_unknown' && <Link className="btn-ghost sm" to="/settings/llm">Model prices</Link>}
        {next?.label === 'Prepare the task' && <button className="btn sm" type="button" onClick={() => open('prepare')}>Prepare the task</button>}
        {item.gate?.state === 'OPEN' && <button className="btn sm" type="button" onClick={() => open('decide')}>Review the {item.gate.phase} decision</button>}
        <WorkItemActions key={`${item.id}:${item.revision}`} item={item} started={start}
          changed={(message, started) => started === action.current ? reread(message) : setRefresh(value => value + 1)} />
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
      {panel === 'decide' && <DecisionPanel key={item.id} itemId={item.id} title={tracker.value?.title ?? null} onClose={close}
        onDecided={message => { close(); reread(message); }} />}
      {panel === 'prepare' && <SidePanel title="Prepare the task" subtitle={`${item.issueKey} · ${item.repository}`} busy={false} onClose={close}
        actions={<button className="btn-ghost" type="button" onClick={close}>Close</button>}>
        <WorkItemPreparation key={`preparation:${item.id}:${item.revision}`} item={item} changed={() => { close(); reread('The prepared task was registered.'); }} />
      </SidePanel>}
    </>}
  </div></section>;
}
