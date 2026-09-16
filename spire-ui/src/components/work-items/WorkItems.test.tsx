import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes, useParams } from 'react-router';
import { useLayoutEffect } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../../api';
import * as auth from '../../auth';
import type { WorkItemDetail as Detail, WorkItemSummary } from '../../api';
import * as approvalsApi from './approvalsApi';
import * as preparationApi from './workPreparationApi';
import WorkItems from './WorkItems';
import WorkItemDetail from './WorkItemDetail';

afterEach(cleanup);
beforeEach(() => { vi.spyOn(api, 'getWorkItemTracker').mockRejectedValue(new Error('TEST-no tracker in list tests')); });

function item(index = 0): WorkItemSummary {
  return { id: `TEST-item-${index}`, sourceId: 'TEST-source', repositoryId: 'TEST-repository',
    repository: 'TEST-owner/TEST-repo', issueKey: `TEST-${index}`, trackerUrl: `https://github.example.test/TEST/repo/issues/${index}`,
    generation: 1, phase: 'spec', workflowStatus: 'awaiting_input', reason: 'A specification is required.',
    profile: { id: 'TEST-profile', name: 'TEST-suggest', version: 1 }, revision: 1, updatedAt: '2026-09-13T12:00:00Z' };
}
function detail(): Detail {
  return { ...item(), effectiveModes: { PLAN: 'approve' }, admittedModes: { PLAN: 'approve' }, policyReason: 'policy_clamped',
    ceiling: item().profile, appliedLabels: [{ label: 'TEST-autonomous', actorId: '900123', origin: 'AUDIT_TRAIL', eventId: 'TEST-event', profileId: 'TEST-profile', profileVersion: 1 }],
    ignoredLabels: [], events: [{ sequence: 0, type: 'Admitted', reason: 'Allowed current label.', occurredAt: item().updatedAt }] };
}
function prepared(): NonNullable<Detail['preparation']> {
  const artifact = (key: string) => ({ sha256: key.repeat(64).slice(0, 64), location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: key }, issueKey: key, link: `https://TEST.example/${key}` } });
  return { specification: artifact('71'), plan: artifact('72'), baseBranch: 'main', baseCommit: 'c'.repeat(40), harness: 'TEST-harness', model: 'TEST-model', registeredBy: 'TEST-operator' };
}
function showDetail() {
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0']}><Link to="/work-items/TEST-item-1">TEST-next item</Link><Routes>
    <Route path="/work-items/:id" element={<WorkItemDetail />} />
  </Routes></MemoryRouter>);
}

it('resets pagination when choosing a filter', async () => {
  vi.spyOn(api, 'getWorkItems').mockImplementation(async (offset = 0, limit = 50) => ({ items: [item(offset)], total: 100, offset, limit }));
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  fireEvent.click(await screen.findByRole('button', { name: 'Next page' }));
  await waitFor(() => expect(api.getWorkItems).toHaveBeenLastCalledWith(50, 50, []));
  fireEvent.click(screen.getByRole('button', { name: /^Needs you/ }));
  await waitFor(() => expect(api.getWorkItems).toHaveBeenLastCalledWith(0, 50, ['waiting_approval', 'awaiting_input', 'suspended']));
});

it('rechecks the displayed item revision before resuming', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(api, 'resumeWorkItem').mockResolvedValue({ reason: 'phase_started' });showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  await waitFor(() => expect(api.resumeWorkItem).toHaveBeenCalledWith(detail(), false));
});

it('requires an operator note to resume suspended work and shows the recorded head', async () => {
  const suspended = { ...detail(), workflowStatus: 'suspended', preparation: prepared(), control: { operator: '900123', note: 'TEST-human takeover', observedHead: 'b'.repeat(40) } };
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(suspended);
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(api, 'resumeWorkItem').mockResolvedValue({ reason: 'phase_started' });showDetail();
  const resume = await screen.findByRole('button', { name: 'Recheck and resume' });expect(resume).toBeDisabled();
  expect(screen.getByText(/Operator: 900123/)).toHaveTextContent('TEST-human takeover');
  expect(screen.getByText(/Observed head:/)).toHaveTextContent('b'.repeat(40));
  fireEvent.change(screen.getByLabelText('Resume note', { selector: 'textarea' }), { target: { value: 'TEST-reviewed human changes' } });
  expect(resume).toBeEnabled();fireEvent.click(resume);
  await waitFor(() => expect(api.resumeWorkItem).toHaveBeenCalledWith(suspended, false, 'TEST-reviewed human changes'));
});

it('never offers resume or readmission for a retired identity', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'retired' });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();await screen.findByText('TEST-title');await act(async () => {});
  expect(screen.queryByRole('button', { name: 'Recheck and resume' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Re-admit under current policy' })).not.toBeInTheDocument();
});

it('shows a refused resume without claiming that work continued', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(api, 'resumeWorkItem').mockRejectedValue(new Error('TEST-409 item changed'));showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-409 item changed');
  expect(screen.getByText('Awaiting input')).toBeInTheDocument();
});

it('offers no resume or readmission action to a viewer', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });showDetail();
  await screen.findByText('TEST-title');await act(async () => {});
  expect(screen.queryByRole('button', { name: 'Recheck and resume' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Re-admit under current policy' })).not.toBeInTheDocument();
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

it('ignores an older list response after refresh', async () => {
  const old = deferred<api.WorkItemPage>();
  vi.spyOn(api, 'getWorkItems').mockReturnValueOnce(old.promise)
    .mockResolvedValue({ items: [item(1)], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: /^Running/ }));
  await screen.findByText('TEST-1');
  await act(async () => old.resolve({ items: [item(0)], total: 1, offset: 0, limit: 50 }));
  expect(screen.getByText('TEST-1')).toBeInTheDocument();
  expect(screen.queryByText('TEST-0')).toBeNull();
});

it('ignores an older list error after refresh', async () => {
  const old = deferred<api.WorkItemPage>();
  vi.spyOn(api, 'getWorkItems').mockReturnValueOnce(old.promise)
    .mockResolvedValue({ items: [item(1)], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: /^Running/ }));
  await screen.findByText('TEST-1');
  await act(async () => old.reject(new Error('TEST-old failure')));
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByText('TEST-1')).toBeInTheDocument();
});

it('ignores an older workflow response after navigation', async () => {
  const old = deferred<Detail>();
  vi.spyOn(api, 'getWorkItem').mockReturnValueOnce(old.promise).mockResolvedValue({ ...detail(), ...item(1) });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-current title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();
  fireEvent.click(screen.getByRole('link', { name: 'TEST-next item' }));
  await screen.findByText('TEST-1 · TEST-owner/TEST-repo');
  await act(async () => old.resolve(detail()));
  expect(screen.getByText('TEST-1 · TEST-owner/TEST-repo')).toBeInTheDocument();
});

it('ignores an older workflow error after navigation', async () => {
  const old = deferred<Detail>();
  vi.spyOn(api, 'getWorkItem').mockReturnValueOnce(old.promise).mockResolvedValue({ ...detail(), ...item(1) });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-current title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail(); fireEvent.click(screen.getByRole('link', { name: 'TEST-next item' }));
  await screen.findByText('TEST-1 · TEST-owner/TEST-repo');
  await act(async () => old.reject(new Error('TEST-old workflow failure')));
  expect(screen.queryByRole('alert')).toBeNull();
});

it('ignores an older tracker response after navigation', async () => {
  const old = deferred<api.WorkItemTracker>();
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), ...item(1) });
  vi.spyOn(api, 'getWorkItemTracker').mockReturnValueOnce(old.promise)
    .mockResolvedValue({ title: 'TEST-current title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail(); fireEvent.click(screen.getByRole('link', { name: 'TEST-next item' }));
  await screen.findByText('TEST-current title');
  await act(async () => old.resolve({ title: 'TEST-old title', body: 'TEST-old body', trackerStatus: 'closed' }));
  expect(screen.getByText('TEST-current title')).toBeInTheDocument();
  expect(screen.queryByText('TEST-old title')).toBeNull();
});

it('ignores an older tracker error after navigation', async () => {
  const old = deferred<api.WorkItemTracker>();
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), ...item(1) });
  vi.spyOn(api, 'getWorkItemTracker').mockReturnValueOnce(old.promise)
    .mockResolvedValue({ title: 'TEST-current title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail(); fireEvent.click(screen.getByRole('link', { name: 'TEST-next item' }));
  await screen.findByText('TEST-current title');
  await act(async () => old.reject(new Error('TEST-old tracker failure')));
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByText('TEST-current title')).toBeInTheDocument();
});

it('loads the second persisted page rather than filtering a fixed window', async () => {
  const first = Array.from({ length: 50 }, (_, index) => item(index));
  vi.spyOn(api, 'getWorkItems').mockImplementation(async (offset = 0) => ({
    items: offset === 0 ? first : [item(50)], total: 51, offset, limit: 50,
  }));
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  await screen.findByText('TEST-0');
  expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
  await screen.findByText('TEST-50');
  expect(api.getWorkItems).toHaveBeenLastCalledWith(50, 50, []);
  expect(screen.queryByText('TEST-0')).toBeNull();
  expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Previous page' }));
  await screen.findByText('TEST-0');
  expect(api.getWorkItems).toHaveBeenLastCalledWith(0, 50, []);
});

it('renders an unknown workflow status as unknown and refused', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [{ ...item(), workflowStatus: 'TEST-future' }], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  expect(await screen.findByText('Unknown (TEST-future)')).toHaveClass('refused');
  // The filter may offer Completed; an unknown result must never render a successful status pill.
  expect(screen.queryByText('Completed', { selector: '.pill' })).toBeNull();
});

it('keeps the workflow visible when the tracker cannot be fetched', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockRejectedValue(new Error('TEST-forge read failed'));
  showDetail();
  await screen.findByText('Awaiting input');
  expect(await screen.findByRole('alert')).toHaveTextContent('Tracker unavailable');
  expect(screen.getByText('A specification is required.')).toBeInTheDocument();
  expect(screen.getByText('Admitted: Allowed current label.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Open ticket in tracker' })).toHaveAttribute('href', item().trackerUrl);
  expect(screen.getByRole('table')).toHaveTextContent('planapproveapprove');
  expect(screen.getByText(/added by an unknown person/)).toHaveTextContent(/900123, audit trail/);
});

it('shows the ignored reason and no selected profile', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), profile: null, workflowStatus: 'not_eligible',
    ignoredLabels: [{ label: 'TEST-autonomous', reason: 'Current label applier is unattributed.', actorId: '900123', origin: 'UNATTRIBUTED' }] });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-live title', body: 'TEST-live body', trackerStatus: 'open' });
  showDetail();
  await screen.findByText('Not eligible');
  expect(within(screen.getByRole('listitem', { name: 'Step 1: Picked up' })).getByText('No profile selected')).toBeInTheDocument();
  expect(screen.getByText(/Current label applier is unattributed/)).toBeInTheDocument();
});

it('keeps tracker status distinct from workflow status', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-live title', body: 'TEST-live body', trackerStatus: 'closed' });
  showDetail();
  await screen.findByText('Tracker status: closed');
  expect(screen.getByText('Awaiting input')).toBeInTheDocument();
  expect(screen.getByText('TEST-live title')).toBeInTheDocument();
  expect(screen.queryByText('Completed')).toBeNull();
});

it('shows a list failure and recovers when refreshed', async () => {
  vi.spyOn(api, 'getWorkItems').mockRejectedValueOnce(new Error('TEST-list failure'))
    .mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-list failure');
  fireEvent.click(screen.getByRole('button', { name: 'Refresh work items' }));
  await screen.findByText('TEST-0');
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
});

// A read with no visible progress reads as a dead button; the label carries the state.
it('says it is refreshing while the list reloads', async () => {
  let release!: (page: { items: WorkItemSummary[]; total: number; offset: number; limit: number }) => void;
  vi.spyOn(api, 'getWorkItems').mockResolvedValueOnce({ items: [item()], total: 1, offset: 0, limit: 50 })
    .mockReturnValueOnce(new Promise(resolve => { release = resolve; }));
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  fireEvent.click(await screen.findByRole('button', { name: 'Refresh work items' }));
  expect(await screen.findByRole('button', { name: 'Refreshing…' })).toBeDisabled();
  // The rows stay while the list re-reads; blanking them would lose the place of the reader.
  expect(screen.getByText('TEST-0')).toBeInTheDocument();
  expect(document.querySelector('[aria-busy="true"]')).not.toBeNull();
  await act(async () => release({ items: [item()], total: 1, offset: 0, limit: 50 }));
  expect(await screen.findByRole('button', { name: 'Refresh work items' })).toBeEnabled();
});

// The ticket key is a bare number on GitHub. The title says what the work is.
it('heads the detail with the ticket title and keeps the key beside it', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-headline', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();
  expect(await screen.findByRole('heading', { level: 2, name: 'TEST-headline' })).toBeInTheDocument();
  expect(screen.getByText((_text, node) => node?.textContent === 'TEST-0 · TEST-owner/TEST-repo')).toBeTruthy();
});

// The tracker read fails on its own, and the item must stay readable when it does.
it('heads the detail with the key while the tracker read is unavailable', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockRejectedValue(new Error('TEST-forge read failed'));
  showDetail();
  expect(await screen.findByRole('heading', { level: 2, name: 'TEST-0' })).toBeInTheDocument();
});

// A label carries a stable provider id. Shown alone it reads as "actor 900123", which names nobody.
it('shows who applied a label by the handle the tracker knows', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(),
    people: [{ providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST Person' }] });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();
  expect(await screen.findByText(/added by @TEST-person/)).toHaveTextContent('900123');
});

it('says the applier is unknown rather than showing a bare id as a name', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), people: [] });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();
  expect(await screen.findByText(/added by an unknown person/)).toBeInTheDocument();
});

// A recheck stopped by a moved ticket said only "artifacts changed"; the operator had to guess which.
it('names the rule that stopped a recheck and keeps it after the page re-reads', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(api, 'resumeWorkItem').mockResolvedValue({ reason: 'artifacts_changed', detail: 'plan_changed' });
  showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  expect(await screen.findByText('The plan ticket changed after it was checked. Check the references again.')).toBeInTheDocument();
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(2));
  expect(screen.getByText('The plan ticket changed after it was checked. Check the references again.')).toBeInTheDocument();
});

it('adds no notice when a recheck names no rule', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(api, 'resumeWorkItem').mockResolvedValue({ reason: 'phase_started' });
  showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(2));
  expect(screen.queryByText(/changed after it was checked/)).toBeNull();
});

// The band says how many items wait on a person across every page, and takes the reader to them.
it('counts the items that need a person and shows them on request', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 4, offset: 0, limit: 50, counts: { waiting_approval: 1, awaiting_input: 2, active: 1 } });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  expect(await screen.findByRole('region', { name: 'Needs you' })).toHaveTextContent('3 items need you');
  expect(screen.getByRole('button', { name: /^Needs you/ })).toHaveTextContent('3');
  expect(screen.getByRole('button', { name: /^All/ })).toHaveTextContent('4');
  fireEvent.click(screen.getByRole('button', { name: 'Show them' }));
  await waitFor(() => expect(api.getWorkItems).toHaveBeenLastCalledWith(0, 50, ['waiting_approval', 'awaiting_input', 'suspended']));
});
it('shows no band when nobody is needed', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50, counts: { active: 1 } });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  await screen.findByText('TEST-0');
  expect(screen.queryByRole('region', { name: 'Needs you' })).toBeNull();
});
// A decision opens beside the list, from the row that needs it; approving never needs a second page.
it('opens an open decision from its row in a side panel', async () => {
  const waiting = { ...item(), workflowStatus: 'waiting_approval', phase: 'plan', gate: { id: 'TEST-gate', version: 1, state: 'OPEN' as const, phase: 'plan', generation: 1,
    itemRevision: 1, policyRevision: 1, artifact: null, openedAt: '2026-09-13T12:00:00Z', expiresAt: '2999-01-01T00:00:00Z', resolver: null, channel: null, note: null } };
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [waiting], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  expect(await screen.findByRole('link', { name: 'Review the plan decision' })).toHaveAttribute('href', '/work-items?filter=needs-you&decide=TEST-item-0');
});
it('renders the decision panel named by the address', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(approvalsApi, 'approvals').mockResolvedValue([]);
  render(<MemoryRouter initialEntries={['/work-items?filter=needs-you&decide=TEST-item-0']}><WorkItems /></MemoryRouter>);
  expect(await screen.findByRole('dialog', { name: 'Decision' })).toHaveTextContent('This item has no open decision.');
});
// Work moves in minutes. The list re-reads by itself, keeps its rows while it does, and says when.
it('re-reads the list on a timer without blanking the rows', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  try {
    vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
    render(<MemoryRouter><WorkItems /></MemoryRouter>);
    await screen.findByText('TEST-0');
    expect(api.getWorkItems).toHaveBeenCalledTimes(1);
    await act(async () => { vi.advanceTimersByTime(15_000); });
    await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(2));
    expect(screen.getByText('TEST-0')).toBeInTheDocument();
    expect(screen.getByText(/^Updated /)).toBeInTheDocument();
  } finally { vi.useRealTimers(); }
});
it('says the list is stale when a re-read fails, and keeps the rows', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValueOnce({ items: [item()], total: 1, offset: 0, limit: 50 }).mockRejectedValueOnce(new Error('TEST-list outage'));
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  fireEvent.click(await screen.findByRole('button', { name: 'Refresh work items' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-list outage');
  expect(screen.getByText('TEST-0')).toBeInTheDocument();
  expect(screen.getByText(/^Not updated since /)).toBeInTheDocument();
});
it('heads a row with its ticket title once the tracker answers', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  vi.mocked(api.getWorkItemTracker).mockResolvedValue({ title: 'TEST-row title', body: 'TEST-body', trackerStatus: 'open' });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  expect(await screen.findByRole('link', { name: 'TEST-row title' })).toHaveAttribute('href', '/work-items/TEST-item-0');
  expect(screen.getByText('TEST-0 · TEST-owner/TEST-repo')).toBeInTheDocument();
});
it('says a cost is unknown rather than showing the priced part as the total', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [{ ...item(), progress: { costMillicents: 9521, usageUnknown: true } }], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  expect(await screen.findByText('unknown')).toBeInTheDocument();
  expect(screen.queryByText('$0.095')).toBeNull();
});

// Review finding: a rule from an earlier recheck stayed beside later, different outcomes.
it('clears the rule notice when the page is refreshed or a later action starts', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(api, 'resumeWorkItem').mockResolvedValueOnce({ reason: 'artifacts_changed', detail: 'plan_changed' })
    .mockReturnValueOnce(new Promise(() => {}));
  showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  const notice = 'The plan ticket changed after it was checked. Check the references again.';
  expect(await screen.findByText(notice)).toBeInTheDocument();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  await waitFor(() => expect(screen.queryByText(notice)).toBeNull());
});
it('clears the rule notice on a manual refresh', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(api, 'resumeWorkItem').mockResolvedValue({ reason: 'artifacts_changed', detail: 'plan_changed' });
  showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  const notice = 'The plan ticket changed after it was checked. Check the references again.';
  expect(await screen.findByText(notice)).toBeInTheDocument();
  const reads = vi.mocked(api.getWorkItem).mock.calls.length;
  fireEvent.click(await screen.findByRole('button', { name: 'Refresh workflow' }));
  // Judge the page after the re-read lands; during it the whole item is a loading line.
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(reads + 1));
  expect(await screen.findByRole('button', { name: 'Refresh workflow' })).toBeInTheDocument();
  expect(screen.queryByText(notice)).toBeNull();
});

// Review finding: polls overlapped, so on a server slower than the interval no answer was ever shown.
it('never starts a poll while a read is still out', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  try {
    vi.spyOn(api, 'getWorkItems').mockResolvedValueOnce({ items: [item()], total: 1, offset: 0, limit: 50 }).mockReturnValue(new Promise(() => {}));
    render(<MemoryRouter><WorkItems /></MemoryRouter>);
    await screen.findByText('TEST-0');
    await act(async () => { vi.advanceTimersByTime(15_000); });
    await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(2));
    await act(async () => { vi.advanceTimersByTime(45_000); });
    expect(api.getWorkItems).toHaveBeenCalledTimes(2);
  } finally { vi.useRealTimers(); }
});
// Review finding: each page turn started three more title reads while the old page's kept going.
it('keeps the tracker read limit across page turns', async () => {
  vi.mocked(api.getWorkItemTracker).mockReturnValue(new Promise(() => {}));
  vi.spyOn(api, 'getWorkItems').mockImplementation(async (offset = 0) => ({
    items: Array.from({ length: 5 }, (_, index) => item(offset + index)), total: 100, offset, limit: 50 }));
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  await screen.findByText('TEST-0');
  await waitFor(() => expect(api.getWorkItemTracker).toHaveBeenCalledTimes(3));
  fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
  await screen.findByText('TEST-50');
  await act(async () => {});
  expect(api.getWorkItemTracker).toHaveBeenCalledTimes(3);
});
// Review finding: moving the address from one decision to another kept the first decision's note and answer.
it('starts a fresh decision when the address names another item', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item(0), item(1)], total: 2, offset: 0, limit: 50 });
  vi.spyOn(api, 'getWorkItem').mockImplementation(async id => ({ ...detail(), id, issueKey: id }));
  const open = (id: string) => ({ workItemId: id, issueKey: id, gate: { id: `${id}-gate`, version: 1, state: 'OPEN' as const, phase: 'plan', generation: 1,
    itemRevision: 1, policyRevision: 1, artifact: null, openedAt: '2026-09-13T12:00:00Z', expiresAt: '2999-01-01T00:00:00Z', resolver: null, channel: null, note: null } });
  vi.spyOn(approvalsApi, 'approvals').mockResolvedValue([open('TEST-item-0'), open('TEST-item-1')]);
  render(<MemoryRouter initialEntries={['/work-items?decide=TEST-item-0']}><Link to="/work-items?decide=TEST-item-1">TEST-next decision</Link><Routes>
    <Route path="/work-items" element={<WorkItems />} /></Routes></MemoryRouter>);
  fireEvent.change(await screen.findByLabelText('Decision note', { selector: 'textarea' }), { target: { value: 'TEST-note for the first item' } });
  fireEvent.click(screen.getByRole('link', { name: 'TEST-next decision' }));
  await waitFor(() => expect(api.getWorkItem).toHaveBeenLastCalledWith('TEST-item-1'));
  expect(await screen.findByLabelText('Decision note', { selector: 'textarea' })).toHaveValue('');
});

// Review finding: a suspended item with no branch to re-observe offered a Resume the server always refuses.
it('offers no resume for a suspended item with nothing to re-observe, and says why', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'suspended', preparation: null });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();
  expect(await screen.findByText(/has no branch to re-observe/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Recheck and resume' })).toBeNull();
});
// Review finding: a finished journey has no current step, so re-admission had nowhere to appear.
it('keeps re-admission reachable once every step is done', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), phase: 'complete', workflowStatus: 'completed', reason: 'all_phases_completed' });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();
  expect(await screen.findByRole('button', { name: 'Re-admit under current policy' })).toBeInTheDocument();
});
// Review finding: a re-read blanked the item, which unmounted an open panel and hid the notice.
it('keeps the item and an open panel on screen while the page re-reads', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValueOnce(detail()).mockReturnValue(new Promise(() => {}));
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(approvalsApi, 'approvals').mockResolvedValue([]);
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0?decide=1']}><Routes><Route path="/work-items/:id" element={<WorkItemDetail />} /></Routes></MemoryRouter>);
  expect(await screen.findByRole('dialog', { name: 'Decision' })).toBeInTheDocument();
  const reads = vi.mocked(api.getWorkItem).mock.calls.length;
  fireEvent.click(screen.getByRole('button', { name: 'Refresh workflow' }));
  // The panel reads the item too, so wait for the page's own re-read to start rather than a count.
  await waitFor(() => expect(vi.mocked(api.getWorkItem).mock.calls.length).toBeGreaterThan(reads));
  await act(async () => {});
  expect(screen.getByRole('dialog', { name: 'Decision' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { level: 2, name: 'TEST-title' })).toBeInTheDocument();
});
it('shows a failed re-read beside the item it could not refresh', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValueOnce(detail()).mockRejectedValueOnce(new Error('TEST-re-read failed'));
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Refresh workflow' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-re-read failed');
  expect(screen.getByRole('heading', { level: 2, name: 'TEST-title' })).toBeInTheDocument();
});
// Review finding: both panels could be open at once from the address.
it('opens one panel at a time', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(approvalsApi, 'approvals').mockResolvedValue([]);
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0?prepare=1&decide=1']}><Routes><Route path="/work-items/:id" element={<WorkItemDetail />} /></Routes></MemoryRouter>);
  expect(await screen.findByRole('dialog', { name: 'Decision' })).toBeInTheDocument();
  expect(screen.getAllByRole('dialog')).toHaveLength(1);
});
// Review finding: a recheck answering after a panel opened brought its notice back and re-read under the panel.
it('ignores a recheck that answers after a panel was opened', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), reason: 'specification_required' });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  let answer!: (value: api.WorkItemOutcome) => void;
  vi.spyOn(api, 'resumeWorkItem').mockReturnValue(new Promise(resolve => { answer = resolve; }));
  showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  fireEvent.click(screen.getByRole('button', { name: 'Prepare the task' }));
  expect(await screen.findByRole('dialog', { name: 'Prepare the task' })).toBeInTheDocument();
  const reads = vi.mocked(api.getWorkItem).mock.calls.length;
  await act(async () => answer({ reason: 'artifacts_changed', detail: 'plan_changed' }));
  expect(screen.queryByText(/The plan ticket changed/)).toBeNull();
  // Its outcome is still real: the page re-reads, under the panel, without the stale notice.
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(reads + 1));
  expect(screen.getByRole('dialog', { name: 'Prepare the task' })).toBeInTheDocument();
});

// Review finding: a manual refresh did not count as an action, so an older recheck could restore its notice.
it('keeps a recheck that answers after a manual refresh from restoring its notice', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  let answer!: (value: api.WorkItemOutcome) => void;
  vi.spyOn(api, 'resumeWorkItem').mockReturnValue(new Promise(resolve => { answer = resolve; }));
  showDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Recheck and resume' }));
  fireEvent.click(screen.getByRole('button', { name: 'Refresh workflow' }));
  await act(async () => answer({ reason: 'artifacts_changed', detail: 'plan_changed' }));
  await act(async () => {});
  expect(screen.queryByText(/The plan ticket changed/)).toBeNull();
});

// The one render between an address change and the effect that clears the old item: a layout effect
// records the committed page before passive effects run, which a plain assertion after act() cannot see.
it('never commits one item under another item address', async () => {
  const committed: { id: string; subtitle: string | null }[] = [];
  function Probe() {
    const { id = '' } = useParams();
    useLayoutEffect(() => { committed.push({ id, subtitle: document.querySelector('.work-head .prov-sub')?.textContent ?? null }); }, [id]);
    return null;
  }
  vi.spyOn(api, 'getWorkItem').mockResolvedValueOnce(detail()).mockReturnValue(new Promise(() => {}));
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0']}><Link to="/work-items/TEST-item-1">TEST-next item</Link><Routes>
    <Route path="/work-items/:id" element={<><WorkItemDetail /><Probe /></>} /></Routes></MemoryRouter>);
  expect(await screen.findByText('TEST-0 · TEST-owner/TEST-repo')).toBeInTheDocument();
  // The probe's selector must find the first item, or a renamed class would let the check pass on nothing.
  expect(document.querySelector('.work-head .prov-sub')?.textContent).toBe('TEST-0 · TEST-owner/TEST-repo');
  fireEvent.click(screen.getByRole('link', { name: 'TEST-next item' }));
  expect(committed.find(entry => entry.id === 'TEST-item-1')).toEqual({ id: 'TEST-item-1', subtitle: null });
});

// M3.5 part C: the specification is a SNAPSHOT of the ticket, so an edit after preparation does not
// change what was approved. Composing again is therefore a deliberate act with its own button, and the
// reason the factory could not compose has to be visible — it is not the workflow's own reason.
it('composes the task again from the ticket, and says why the factory could not', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'awaiting_input', reason: 'specification_required',
    preparationHealth: { reason: 'ticket_body_empty', attempts: 3, lastAt: '2026-09-16T10:00:00Z', retryAfter: '2026-09-16T10:05:00Z' } });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  const compose = vi.spyOn(preparationApi, 'composePreparation').mockResolvedValue({ reason: 'approval_required' });
  showDetail();

  expect(await screen.findByText(/This ticket has no description/)).toBeInTheDocument();
  expect(screen.getByText(/tried 3 times/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Prepare again from the ticket' }));
  await waitFor(() => expect(compose).toHaveBeenCalledWith(detail().id));
});
