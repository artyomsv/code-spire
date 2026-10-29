import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router';
import { getWorkItem, getWorkItemTracker } from '../../api';
import { formatEventTime } from '../../format';
import SidePanel from '../SidePanel';
import DecisionPanel from './DecisionPanel';
import JourneyStrip from './JourneyStrip';
import WorkItemActions from './WorkItemActions';
import WorkItemPolicy from './WorkItemPolicy';
import WorkItemPreparation from './WorkItemPreparation';
import WorkItemSteps from './WorkItemSteps';
import { useWorkItemChanges } from './useWorkItemChanges';
import { workKeys } from './workQueries';
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
  // What the last action reported. It survives the re-read that action triggers, and a new action
  // clears it, so a rule from an earlier recheck never sits beside a later, different outcome.
  const [notice, setNotice] = useState('');
  const client = useQueryClient();
  // Each read is keyed on the item, so an older item's answer lands under its own key and never on this page.
  const itemQuery = useQuery({ queryKey: workKeys.item(id), queryFn: () => getWorkItem(id) });
  const trackerQuery = useQuery({ queryKey: workKeys.tracker(id), queryFn: () => getWorkItemTracker(id) });
  // Every action takes a number. A recheck that answers after a newer action started must not bring
  // its notice back, nor re-read the page under a panel the operator has since opened.
  const action = useRef(0);
  const panel = params.get('decide') ? 'decide' : params.get('prepare') ? 'prepare' : null;
  useEffect(() => { setNotice(''); }, [id]);
  useEffect(() => { if (panel) { action.current++; setNotice(''); } }, [panel]);
  // The page follows the item through the feed, so a gate opening or a build ending shows without a click. Not while
  // a panel is open: a re-read under a decision the operator is reading would move what they approve. Closing the
  // panel reopens the feed, and its open reads again whatever changed meanwhile.
  const feed = useWorkItemChanges({ enabled: !panel });

  /** Reads the item and its ticket again, leaving the notice as it is. */
  function invalidate() {
    void client.invalidateQueries({ queryKey: workKeys.item(id), exact: true });
    void client.invalidateQueries({ queryKey: workKeys.tracker(id), exact: true });
  }
  function reread(message = '') { setNotice(message); invalidate(); }
  function start() { setNotice(''); return ++action.current; }
  function open(name: 'decide' | 'prepare') { setParams({ [name]: '1' }); }
  function close() { setParams({}); }
  // The item on screen must be the one the address names; until its own read lands, show none. Each
  // read is keyed on the id, so this guard only matters if the server ever answered for another item.
  const item = itemQuery.data?.id === id ? itemQuery.data : null;
  const error = itemQuery.error ? String(itemQuery.error) : null;
  const tracker = { value: trackerQuery.data ?? null, error: trackerQuery.error ? String(trackerQuery.error) : null };
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
          {!panel && feed.status === 'down' && <span className="chip warn">Live updates paused</span>}
        </div>
      </div>
      {item.preparationHealth && <p className="prov-error" role="status">
        The factory could not prepare this task: {workReason(item.preparationHealth.reason)}
        {item.preparationHealth.attempts > 1 ? ` (tried ${item.preparationHealth.attempts} times)` : ''}
      </p>}
{/* The prepared specification is a snapshot. Saying when the ticket has moved on is the whole
          reason an operator would press "Prepare again" — and never changes the approved bytes.
          A ticket that can no longer compose counts as changed too: emptying or over-filling it used to
          remove the notice rather than raise it, which is the drift that matters most.
          Composed preparations only: a generated specification is the factory's text, never the ticket's composition,
          so the digests always differ, and no source-ticket digest is kept to compare instead (review of PR 287, M-3). */}
      {!item.generated && item.preparation?.specification.origin === 'STORED' && tracker.value
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
          changed={(message, started) => started === action.current ? reread(message) : invalidate()} />
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
