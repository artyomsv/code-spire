import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { AlertTriangle, ListTodo } from 'lucide-react';
import { getWorkItems, type WorkItemPage, type WorkWorkflowStatus } from '../../api';
import { canAdminister } from '../../auth';
import { formatEventTime } from '../../format';
import { useMe } from '../../hooks/useMe';
import { fetchWorkSources, rescanWorkSource } from './workSourcesApi';
import { workReason } from './workReasons';
import { FILTERS, filterById, nextAction } from './workJourney';
import { useTicketTitles } from './useTicketTitles';
import { useWorkItemChanges } from './useWorkItemChanges';
import { workKeys } from './workQueries';
import WorkItemTable from './WorkItemTable';
import DecisionPanel from './DecisionPanel';
import PastDecisions from './PastDecisions';

// Re-exported so the screens that already import it from here keep one import path.
export { workReason };

const PAGE_SIZE = 50;

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
} satisfies Record<WorkWorkflowStatus, { label: string; tone: string }>));

export function WorkflowStatus({ status }: { status: string }) {
  const state = STATES.get(status) ?? { label: `Unknown (${status})`, tone: 'refused' };
  return <span className={`pill ${state.tone}`}>{state.label}</span>;
}

function countOf(page: WorkItemPage | null, statuses: string[]): number | null {
  if (!page?.counts) return null;
  const counts = page.counts;
  return (statuses.length ? statuses : Object.keys(counts)).reduce((sum, status) => sum + (counts[status] ?? 0), 0);
}

export default function WorkItems() {
  const [params, setParams] = useSearchParams();
  const filter = filterById(params.get('filter'));
  const [offset, setOffset] = useState(0);
  const [notice, setNotice] = useState(''), [scanning, setScanning] = useState(false);
  const admin = canAdminister(useMe().me);
  const client = useQueryClient();
  // A page is keyed on the filter and offset it answers, so a new filter never shows the old rows, and a read for
  // another filter can never land here.
  const query = useQuery({ queryKey: workKeys.list(filter.id, offset), queryFn: () => getWorkItems(offset, PAGE_SIZE, filter.statuses) });
  const feed = useWorkItemChanges();
  const page = query.data ?? null;
  const error = query.error ? String(query.error) : null;
  const loading = query.isFetching;
  const updatedAt = query.dataUpdatedAt ? new Date(query.dataUpdatedAt).toISOString() : null;
  const reread = () => void client.invalidateQueries({ queryKey: workKeys.lists() });

  const rows = page?.items ?? [];
  const titles = useTicketTitles(rows.map(row => row.id));
  const needsFilter = filterById('needs-you');
  const needs = countOf(page, needsFilter.statuses);
  const deciding = params.get('decide');
  function choose(id: string) { setOffset(0); setParams(id === 'all' ? {} : { filter: id }); }
  function without(key: string) { const next = new URLSearchParams(params); next.delete(key); setParams(next); }
  // A source is re-read every 5 minutes unless a scan is requested, so Refresh asks for one first.
  // The request only sets a flag; the scanner reads the tracker on its next sweep, about 30 seconds
  // later, and the feed shows what it admitted. A failed request never stops the list re-reading.
  // Only an admin may request a scan, so for a viewer Refresh re-reads the list alone.
  async function refreshNow() {
    if (admin) {
      setScanning(true);
      try {
        const sources = (await fetchWorkSources()).filter(source => source.enabled);
        const results = await Promise.allSettled(sources.map(source => rescanWorkSource(source.id)));
        const failed = sources.filter((_source, index) => results[index].status === 'rejected').map(source => source.name);
        setNotice(failed.length ? `Could not request a tracker scan of ${failed.join(', ')}. The list was re-read anyway.`
          : sources.length ? 'Tracker scan requested. New tickets appear within about a minute.' : 'No enabled work source to scan.');
      } catch (failure) { setNotice(`Could not request a tracker scan: ${String(failure)}. The list was re-read anyway.`); }
      finally { setScanning(false); }
    }
    reread();
  }

  return <section className="content"><div className="card">
    <div className="prov-head">
      <h2 className="prov-title">Work items</h2>
      {updatedAt && <span className={error ? 'chip warn' : 'prov-sub'}>{error ? `Not updated since ${formatEventTime(updatedAt)}` : `Updated ${formatEventTime(updatedAt)}`}</span>}
      {feed.status === 'down' && <span className="chip warn">Live updates paused</span>}
      <div className="prov-actions">
        <button className="btn-ghost" type="button" onClick={() => setParams({ ...Object.fromEntries(params), history: '1' })}>Past decisions</button>
        <button className="btn" type="button" disabled={loading || scanning} onClick={() => void refreshNow()}>{loading || scanning ? 'Refreshing…' : 'Refresh work items'}</button>
      </div>
    </div>
    {notice && <p className="prov-note" role="status">{notice}</p>}
    <p className="prov-note">{admin
      ? 'A labelled ticket is read from the tracker every 5 minutes, or within about 30 seconds when you press Refresh, then prepared. It can take a minute to appear here.'
      : 'A labelled ticket is read from the tracker every 5 minutes, then prepared. It can take a few minutes to appear here.'} This list refreshes by itself.</p>
    {needs !== null && needs > 0 && <div className="attn" role="region" aria-label="Needs you">
      <AlertTriangle size={17} aria-hidden="true" />
      <span className="grow"><b>{needs === 1 ? '1 item needs you' : `${needs} items need you`}</b>
        {rows.filter(row => nextAction(row)).slice(0, 3).map(row => <span key={row.id} className="attn-item"> · <Link to={nextAction(row)!.to}>{titles.get(row.id) ?? row.issueKey}: {nextAction(row)!.label}</Link></span>)}</span>
      {filter.id !== 'needs-you' && <button className="btn sm" type="button" onClick={() => choose('needs-you')}>Show them</button>}
    </div>}
    <div className="chips work-filters" role="group" aria-label="Filter work items">
      {FILTERS.map(entry => { const count = countOf(page, entry.statuses);
        return <button key={entry.id} type="button" className={entry.id === filter.id ? 'chip on' : 'chip'} aria-pressed={entry.id === filter.id} onClick={() => choose(entry.id)}>
          {entry.label}{count !== null && <span className="n">{count}</span>}</button>; })}
    </div>
    {error && !page ? <p className="prov-error" role="alert">{error}</p> : !page ? <p className="prov-note" role="status" aria-busy="true">Loading work items…</p> : <>
      {error && <p className="prov-error" role="alert">{error}</p>}
      {rows.length === 0 ? <div className="wh-empty">
        <div className="wh-empty-icon"><ListTodo size={22} aria-hidden="true" /></div>
        <div className="wh-empty-title">{filter.id === 'all' ? 'No work items yet.' : 'No work items match this filter.'}</div>
        <p className="wh-empty-text">{filter.id === 'all' ? 'Registered sources admit tickets after a scan or label event.' : 'Choose another filter to see more items.'}</p>
      </div> : <WorkItemTable rows={rows} titles={titles} busy={loading} />}
      <div className="prov-actions work-pages">
        <button className="btn-ghost" type="button" disabled={page.offset === 0} onClick={() => setOffset(Math.max(0, page.offset - page.limit))}>Previous page</button>
        <span className="prov-sub">{page.total === 0 ? '0 items' : `${page.offset + 1}–${page.offset + rows.length} of ${page.total}`}</span>
        <button className="btn-ghost" type="button" disabled={page.offset + rows.length >= page.total} onClick={() => setOffset(page.offset + page.limit)}>Next page</button>
      </div>
    </>}
    {deciding && <DecisionPanel key={deciding} itemId={deciding} title={titles.get(deciding) ?? null} onClose={() => without('decide')}
      onDecided={message => { setNotice(message); without('decide'); reread(); }} />}
    {params.get('history') && <PastDecisions onClose={() => without('history')} />}
  </div></section>;
}
