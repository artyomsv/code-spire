import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes, useParams } from 'react-router';
import { useLayoutEffect } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from '../../api';
import * as auth from '../../auth';
import type { WorkItemDetail as Detail, WorkItemSummary } from '../../api';
import * as approvalsApi from './approvalsApi';
import * as preparationApi from './workPreparationApi';
import * as sourcesApi from './workSourcesApi';
import WorkItems from './WorkItems';
import WorkItemDetail from './WorkItemDetail';
import { queryWrapper } from '../../test/queryWrapper';
import { FakeSocket } from '../../test/fakeSocket';

afterEach(cleanup);
beforeEach(() => {
  vi.spyOn(api, 'getWorkItemTracker').mockRejectedValue(new Error('TEST-no tracker in list tests'));
  FakeSocket.sockets = [];
  vi.stubGlobal('WebSocket', FakeSocket);
});

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
  </Routes></MemoryRouter>, { wrapper: queryWrapper() });
}

it('resets pagination when choosing a filter', async () => {
  vi.spyOn(api, 'getWorkItems').mockImplementation(async (offset = 0, limit = 50) => ({ items: [item(offset)], total: 100, offset, limit }));
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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
  expect(resume).toHaveAccessibleDescription('Write a resume note to unlock Recheck and resume.');
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
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-list failure');
  fireEvent.click(screen.getByRole('button', { name: 'Refresh work items' }));
  await screen.findByText('TEST-0');
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
});

function source(id: string, enabled = true): sourcesApi.WorkSource {
  return { id, name: `${id} name`, type: 'GITHUB', origin: 'https://github.example.test', projectId: 'TEST-project', scope: 'TEST-owner/TEST-repo',
    repositoryId: 'TEST-repository', accountId: 'TEST-account', enabled, configuredEnabled: enabled, version: { source: 1, account: 1, repository: 1 },
    cursor: null, health: 'healthy', allowedPeople: [], repository: { workspace: 'TEST-owner', slug: 'TEST-repo' } };
}
async function showAsAdmin() {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  await screen.findByText(/when you press Refresh/);
  await screen.findByText('TEST-0');
}

// A source is re-read every 5 minutes; without a requested scan a new ticket waits that long.
it('requests a scan of each enabled source and re-reads the list when refreshed', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  vi.spyOn(sourcesApi, 'fetchWorkSources').mockResolvedValue([source('TEST-source-a'), source('TEST-source-off', false), source('TEST-source-b')]);
  vi.spyOn(sourcesApi, 'rescanWorkSource').mockResolvedValue(undefined);
  await showAsAdmin();
  fireEvent.click(screen.getByRole('button', { name: 'Refresh work items' }));
  await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(2));
  expect(sourcesApi.rescanWorkSource).toHaveBeenCalledTimes(2);
  expect(sourcesApi.rescanWorkSource).toHaveBeenCalledWith('TEST-source-a');
  expect(sourcesApi.rescanWorkSource).toHaveBeenCalledWith('TEST-source-b');
  expect(screen.getByRole('status')).toHaveTextContent('Tracker scan requested.');
});

it('re-reads the list and says so when a scan request fails', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  vi.spyOn(sourcesApi, 'fetchWorkSources').mockResolvedValue([source('TEST-source-a'), source('TEST-source-b')]);
  vi.spyOn(sourcesApi, 'rescanWorkSource').mockImplementation(async id => { if (id === 'TEST-source-a') throw new Error('TEST-scan refused'); });
  await showAsAdmin();
  fireEvent.click(screen.getByRole('button', { name: 'Refresh work items' }));
  await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(2));
  expect(sourcesApi.rescanWorkSource).toHaveBeenCalledWith('TEST-source-b');
  expect(await screen.findByRole('status')).toHaveTextContent('Could not request a tracker scan of TEST-source-a name.');
  expect(screen.getByText('TEST-0')).toBeInTheDocument();
});

// A viewer may not request a scan, so Refresh re-reads the list without asking for one.
it('only re-reads the list for a viewer', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  vi.spyOn(sourcesApi, 'fetchWorkSources').mockResolvedValue([source('TEST-source-a')]);
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  fireEvent.click(await screen.findByRole('button', { name: 'Refresh work items' }));
  await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(2));
  expect(sourcesApi.fetchWorkSources).not.toHaveBeenCalled();
});

// A read with no visible progress reads as a dead button; the label carries the state.
it('says it is refreshing while the list reloads', async () => {
  let release!: (page: { items: WorkItemSummary[]; total: number; offset: number; limit: number }) => void;
  vi.spyOn(api, 'getWorkItems').mockResolvedValueOnce({ items: [item()], total: 1, offset: 0, limit: 50 })
    .mockReturnValueOnce(new Promise(resolve => { release = resolve; }));
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  expect(await screen.findByRole('region', { name: 'Needs you' })).toHaveTextContent('3 items need you');
  expect(screen.getByRole('button', { name: /^Needs you/ })).toHaveTextContent('3');
  expect(screen.getByRole('button', { name: /^All/ })).toHaveTextContent('4');
  fireEvent.click(screen.getByRole('button', { name: 'Show them' }));
  await waitFor(() => expect(api.getWorkItems).toHaveBeenLastCalledWith(0, 50, ['waiting_approval', 'awaiting_input', 'suspended']));
});
it('shows no band when nobody is needed', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50, counts: { active: 1 } });
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  await screen.findByText('TEST-0');
  expect(screen.queryByRole('region', { name: 'Needs you' })).toBeNull();
});
// A decision opens beside the list, from the row that needs it; approving never needs a second page.
it('opens an open decision from its row in a side panel', async () => {
  const waiting = { ...item(), workflowStatus: 'waiting_approval', phase: 'plan', gate: { id: 'TEST-gate', version: 1, state: 'OPEN' as const, phase: 'plan', generation: 1,
    itemRevision: 1, policyRevision: 1, artifact: null, openedAt: '2026-09-13T12:00:00Z', expiresAt: '2999-01-01T00:00:00Z', resolver: null, channel: null, note: null } };
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [waiting], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  expect(await screen.findByRole('link', { name: 'Review the plan decision' })).toHaveAttribute('href', '/work-items?filter=needs-you&decide=TEST-item-0');
});
it('renders the decision panel named by the address', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  vi.spyOn(approvalsApi, 'approvals').mockResolvedValue([]);
  render(<MemoryRouter initialEntries={['/work-items?filter=needs-you&decide=TEST-item-0']}><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  expect(await screen.findByRole('dialog', { name: 'Decision' })).toHaveTextContent('This item has no open decision.');
});
it('says the list is stale when a re-read fails, and keeps the rows', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValueOnce({ items: [item()], total: 1, offset: 0, limit: 50 }).mockRejectedValueOnce(new Error('TEST-list outage'));
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  fireEvent.click(await screen.findByRole('button', { name: 'Refresh work items' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-list outage');
  expect(screen.getByText('TEST-0')).toBeInTheDocument();
  expect(screen.getByText(/^Not updated since /)).toBeInTheDocument();
});
it('heads a row with its ticket title once the tracker answers', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [item()], total: 1, offset: 0, limit: 50 });
  vi.mocked(api.getWorkItemTracker).mockResolvedValue({ title: 'TEST-row title', body: 'TEST-body', trackerStatus: 'open' });
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  expect(await screen.findByRole('link', { name: 'TEST-row title' })).toHaveAttribute('href', '/work-items/TEST-item-0');
  expect(screen.getByText('TEST-0 · TEST-owner/TEST-repo')).toBeInTheDocument();
});
it('says a cost is unknown rather than showing the priced part as the total', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [{ ...item(), progress: { costMillicents: 9521, usageUnknown: true } }], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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

// Review finding: each page turn started three more title reads while the old page's kept going.
it('keeps the tracker read limit across page turns', async () => {
  vi.mocked(api.getWorkItemTracker).mockReturnValue(new Promise(() => {}));
  vi.spyOn(api, 'getWorkItems').mockImplementation(async (offset = 0) => ({
    items: Array.from({ length: 5 }, (_, index) => item(offset + index)), total: 100, offset, limit: 50 }));
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
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
    <Route path="/work-items" element={<WorkItems />} /></Routes></MemoryRouter>, { wrapper: queryWrapper() });
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
// M4 land (spec §7.9): a merged pull request cannot be built again beside itself, and a handed item goes on with Retry land.
function deliveredExecution(landed: boolean): NonNullable<NonNullable<Detail['progress']>['execution']> {
  const pullRequest = { number: 901, url: 'https://github.example.test/TEST/repo/pull/901', draft: false };
  return { runId: 'TEST-run', build: { workItemId: 'TEST-item-0', generation: 1, buildAttemptId: 'TEST-attempt', preparationBinding: 'a'.repeat(64) },
    head: 'b'.repeat(40), verificationAttempt: null, pullRequest, reviewId: 'TEST-review',
    landing: landed ? { pullRequest, mergeCommit: 'c'.repeat(40), method: 'squash', mergedBy: 'factory', landedAt: '2026-10-22T12:00:00Z' } : null };
}
async function readmitOffered(value: Detail) {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(value);
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();await screen.findByText('TEST-title');await act(async () => {});
  return screen.queryByRole('button', { name: 'Re-admit under current policy' }) != null;
}
it('offers no re-admission for a landed item', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'complete', workflowStatus: 'completed', reason: 'landed', progress: { execution: deliveredExecution(true) } })).toBe(false);
});
it('offers no re-admission while an item waits at land for the operator', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'awaiting_input', reason: 'land_blocked', progress: { execution: deliveredExecution(false) } })).toBe(false);
});
// Review of slice 8: waiting at land for any other reason still keeps the delivered pull request open, so readmit stays hidden (A8);
// Recheck is what answers it.
it('offers Recheck but no re-admission while an item waits at land on unknown usage', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'awaiting_input', reason: 'run_usage_unknown', progress: { execution: deliveredExecution(false) } })).toBe(false);
  expect(screen.getByRole('button', { name: 'Recheck and resume' })).toBeInTheDocument();
});
it('offers no re-admission while an item waits at land for a capability', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'capability_unavailable', reason: 'land_capability_unavailable', progress: { execution: deliveredExecution(false) } })).toBe(false);
});
// M4 land fix (Review Focus 15): readmit there would build a second pull request beside the open one, and the history that says so is the server's.
it('offers no re-admission while a land fix is in progress', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'build', workflowStatus: 'awaiting_input', reason: 'run_usage_unknown', progress: { execution: deliveredExecution(false) },
    land: { state: 'fixing', reason: 'land_checks_failed', message: null, checks: null, method: 'merge', ticketRule: 'comment', branchDeleted: null, note: null,
      attempt: 1, attemptsAllowed: 3, fixInProgress: true, handedOver: false, stoppable: false } })).toBe(false);
});
// M4 slice 3 (§6.11, A7): readmit reuses a generated item's approved specification and plan; Regenerate writes them again.
it('the readmit dialog offers Regenerate only for generated items', async () => {
  const finished: Detail = { ...detail(), phase: 'complete', workflowStatus: 'completed', reason: 'all_phases_completed' };
  expect(await readmitOffered(finished)).toBe(true);
  expect(screen.queryByRole('checkbox', { name: 'Regenerate specification and plan' })).toBeNull();
  cleanup();
  vi.spyOn(api, 'resumeWorkItem').mockResolvedValue({ reason: 'specification_to_write' });
  const generated: Detail = { ...finished, generated: true };
  expect(await readmitOffered(generated)).toBe(true);
  fireEvent.click(screen.getByRole('checkbox', { name: 'Regenerate specification and plan' }));
  fireEvent.click(screen.getByRole('button', { name: 'Re-admit under current policy' }));
  await waitFor(() => expect(api.resumeWorkItem).toHaveBeenCalledWith(generated, true, undefined, true));
});
// M4 land slice 8 (spec §7.6, A3): a handed item goes on with Retry land or Stop, both with a note; Recheck would reopen land without one.
/** The land view the server sends; whether the item was handed over, and whether Stop land ends it, are the server's to say. */
function landView(state: string, handedOver: boolean, stoppable: boolean): NonNullable<Detail['land']> {
  return { state, reason: null, message: null, checks: null, method: 'merge', ticketRule: 'comment', branchDeleted: null, note: null,
    attempt: 0, attemptsAllowed: 3, fixInProgress: false, handedOver, stoppable };
}
function handedItem(reason = 'land_blocked'): Detail {
  return { ...detail(), phase: 'land', workflowStatus: 'awaiting_input', reason, progress: { execution: deliveredExecution(false) },
    land: { ...landView('handed', true, true), reason } };
}
async function showHanded(reason = 'land_blocked') {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(handedItem(reason));
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();await screen.findByText('TEST-title');await act(async () => {});
}
it('handed item shows Retry land and not Recheck', async () => {
  vi.spyOn(api, 'retryLand').mockResolvedValue({ reason: 'approval_required' });
  await showHanded();
  expect(screen.queryByRole('button', { name: 'Recheck and resume' })).toBeNull();
  const retry = screen.getByRole('button', { name: 'Retry land' });expect(retry).toBeDisabled();
  fireEvent.change(screen.getByLabelText('Land note', { selector: 'textarea' }), { target: { value: 'TEST-the code owners approved' } });
  expect(retry).toBeEnabled();fireEvent.click(retry);
  await waitFor(() => expect(api.retryLand).toHaveBeenCalledWith(handedItem(), 'TEST-the code owners approved'));
});
it('handed item offers Stop with the note', async () => {
  vi.spyOn(api, 'stopLand').mockResolvedValue({ reason: 'land_stopped_by_operator' });
  await showHanded();
  const stop = screen.getByRole('button', { name: 'Stop land' });expect(stop).toBeDisabled();
  fireEvent.change(screen.getByLabelText('Land note', { selector: 'textarea' }), { target: { value: 'TEST-merging it by hand' } });
  fireEvent.click(stop);
  await waitFor(() => expect(api.stopLand).toHaveBeenCalledWith(handedItem(), 'TEST-merging it by hand'));
});
// Live land acceptance: Stop land looked enabled with an empty note and a click did nothing. The page says why it waits.
it('says a land note unlocks Retry land and Stop land, and the hint leaves once it is written', async () => {
  vi.spyOn(api, 'stopLand').mockResolvedValue({ reason: 'land_stopped_by_operator' });
  await showHanded();
  const stop = screen.getByRole('button', { name: 'Stop land' });
  expect(stop).toBeDisabled();
  expect(stop).toHaveAccessibleDescription('Write a land note to unlock Retry land and Stop land.');
  expect(screen.getByRole('button', { name: 'Retry land' })).toHaveAccessibleDescription('Write a land note to unlock Retry land and Stop land.');
  fireEvent.click(stop);
  expect(api.stopLand).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('Land note', { selector: 'textarea' }), { target: { value: '   ' } });
  expect(stop).toBeDisabled();
  fireEvent.change(screen.getByLabelText('Land note', { selector: 'textarea' }), { target: { value: 'TEST-merging it by hand' } });
  expect(stop).toBeEnabled();
  expect(screen.queryByText('Write a land note to unlock Retry land and Stop land.')).toBeNull();
});
it('names only Stop land in the hint when Retry land is not offered', async () => {
  await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'not_eligible', reason: 'land_off', progress: { execution: deliveredExecution(false) },
    land: landView('not_started', false, true) });
  expect(screen.getByRole('button', { name: 'Stop land' })).toHaveAccessibleDescription('Write a land note to unlock Stop land.');
});
// Review of slice 9: land unavailable on this forge or for this repository has no other way on, and once land is off
// the item is not eligible at land with its pull request still open. Stop ends either; Retry land follows only a hand-off.
it('offers Stop land but not Retry land while land is unavailable', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'capability_unavailable', reason: 'land_capability_unavailable', progress: { execution: deliveredExecution(false) },
    land: landView('not_started', false, true) })).toBe(false);
  expect(screen.getByRole('button', { name: 'Stop land' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Retry land' })).toBeNull();
});
it('offers Stop land and no re-admission at land once land is off', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'not_eligible', reason: 'land_off', progress: { execution: deliveredExecution(false) },
    land: landView('not_started', false, true) })).toBe(false);
  expect(screen.getByRole('button', { name: 'Stop land' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Retry land' })).toBeNull();
});
// Review of the branch: the hand-off reasons are the server's. A reason the screen has no copy of still offers Retry land.
it('offers Retry land for any reason the server says handed the item over', async () => {
  await showHanded('TEST-land-future-reason');
  expect(screen.getByRole('button', { name: 'Retry land' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Recheck and resume' })).toBeNull();
});
// A land waiting hours for its checks has sent no merge, so Stop land ends it; once the merge was sent it cannot.
it('offers Stop land but not Retry land while land waits for checks', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'active', reason: 'phase_started', progress: { execution: deliveredExecution(false) },
    land: landView('waiting_checks', false, true) })).toBe(false);
  expect(screen.getByRole('button', { name: 'Stop land' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Retry land' })).toBeNull();
});
it('offers no Stop land once the merge was sent', async () => {
  await readmitOffered({ ...detail(), phase: 'land', workflowStatus: 'active', reason: 'phase_started', progress: { execution: deliveredExecution(false) },
    land: landView('uncertain', false, false) });
  expect(screen.queryByRole('button', { name: 'Stop land' })).toBeNull();
});
it('still offers re-admission for a completed item that did not land', async () => {
  expect(await readmitOffered({ ...detail(), phase: 'complete', workflowStatus: 'completed', reason: 'all_phases_completed', progress: { execution: deliveredExecution(false) } })).toBe(true);
});
// Review finding: a re-read blanked the item, which unmounted an open panel and hid the notice.
it('keeps the item and an open panel on screen while the page re-reads', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValueOnce(detail()).mockReturnValue(new Promise(() => {}));
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  vi.spyOn(approvalsApi, 'approvals').mockResolvedValue([]);
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0?decide=1']}><Routes><Route path="/work-items/:id" element={<WorkItemDetail />} /></Routes></MemoryRouter>, { wrapper: queryWrapper() });
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
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0?prepare=1&decide=1']}><Routes><Route path="/work-items/:id" element={<WorkItemDetail />} /></Routes></MemoryRouter>, { wrapper: queryWrapper() });
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
    <Route path="/work-items/:id" element={<><WorkItemDetail /><Probe /></>} /></Routes></MemoryRouter>, { wrapper: queryWrapper() });
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
  fireEvent.click(await screen.findByRole('button', { name: 'Prepare again from the ticket' }));
  await waitFor(() => expect(compose).toHaveBeenCalledWith(detail().id, detail().revision));
});

// A viewer cannot prepare anything, but the reason nothing was prepared is the first thing they ask
// about. Hiding it behind the admin check made the page say nothing at all to everyone else.
it('tells a viewer why the factory could not prepare, and offers them no button', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(),
    preparationHealth: { reason: 'build_defaults_missing', attempts: 1, lastAt: '2026-09-16T10:00:00Z', retryAfter: '2026-09-16T10:05:00Z' } });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();

  expect(await screen.findByText(/This repository has no build setup yet/)).toBeInTheDocument();
  // One attempt is not worth counting out loud; the sentence alone is the answer.
  expect(screen.queryByText(/tried 1 times/)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Prepare again from the ticket' })).not.toBeInTheDocument();
});

// The workflow's own reason is a phase sentence: "a specification is required" is equally true of an
// empty ticket and of a repository with no build setup. The health reason names which one it is.
it('says in the list why the factory could not prepare, not the phase sentence', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [{ ...item(), reason: 'A specification is required.',
    preparationHealth: { reason: 'build_defaults_missing', attempts: 2, lastAt: '2026-09-16T10:00:00Z', retryAfter: '2026-09-16T10:05:00Z' } }],
    total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });

  expect(await screen.findByText(/This repository has no build setup yet/)).toBeInTheDocument();
  expect(screen.queryByText('A specification is required.')).not.toBeInTheDocument();
});

// The specification is a snapshot. An edit does not change what is approved or built — which is the
// point, and also the thing nobody guesses. The page has to say it, to every viewer.
it('says the ticket changed after it was prepared, without claiming the build changed', async () => {
  const snapshot = { ...prepared(), specification: { ...prepared().specification, origin: 'STORED' as const, storedId: 'TEST-stored' } };
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'waiting_approval', preparation: snapshot });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-edited body', trackerStatus: 'open',
    composedSha256: 'e'.repeat(64) });
  showDetail();

  expect(await screen.findByText(/The ticket changed after it was prepared/)).toBeInTheDocument();
});

it('adds no drift notice while the ticket still composes to what was prepared', async () => {
  const snapshot = { ...prepared(), specification: { ...prepared().specification, origin: 'STORED' as const, storedId: 'TEST-stored' } };
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'waiting_approval', preparation: snapshot });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open',
    composedSha256: snapshot.specification.sha256 });
  showDetail();

  await screen.findByText('TEST-title');await act(async () => {});
  expect(screen.queryByText(/The ticket changed after it was prepared/)).not.toBeInTheDocument();
});

// A generated item's specification is the factory's text, not a composition of the ticket, so the composed digest
// never matches it (review of PR 287, M-3). No source-ticket digest is kept to compare, so no drift is claimed, and
// a ticket that could not be composed says nothing about a specification the factory wrote.
it('adds no drift notice to a generated item', async () => {
  const snapshot = { ...prepared(), specification: { ...prepared().specification, origin: 'STORED' as const, storedId: 'TEST-stored' } };
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'waiting_approval', preparation: snapshot, generated: true });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-title-only', trackerStatus: 'open',
    composedSha256: null, composedRefusal: 'ticket_body_empty' });
  showDetail();

  await screen.findByText('TEST-title');await act(async () => {});
  expect(screen.queryByText(/The ticket changed after it was prepared/)).not.toBeInTheDocument();
});

// An assisted item sits at its plan decision. That is exactly when an operator reads the ticket again
// and edits it, so the button has to be reachable there rather than only before anything is prepared.
// A ticket somebody emptied has drifted further from what was prepared than an edited one. It used to
// arrive as an absent digest, which read exactly like "no drift", so the notice vanished when it mattered.
it('says the ticket changed when it can no longer be prepared at all', async () => {
  const snapshot = { ...prepared(), specification: { ...prepared().specification, origin: 'STORED' as const, storedId: 'TEST-stored' } };
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'waiting_approval', preparation: snapshot });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: '', trackerStatus: 'open',
    composedSha256: null, composedRefusal: 'ticket_body_empty' });
  showDetail();

  expect(await screen.findByText(/The ticket changed after it was prepared/)).toBeInTheDocument();
  expect(screen.getByText(/This ticket has no description/)).toBeInTheDocument();
});

// The server refuses a composition once this generation has attempted a phase. A button that can only
// fail is worse than no button: it reads as "the system is broken" rather than "that is not allowed".
it('stops offering to compose again once this generation has built something', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'awaiting_input', preparation: prepared(),
    builds: [{ attemptId: 'TEST-attempt', state: 'held', runId: 'TEST-run', reason: null, generation: detail().generation }] });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();

  await screen.findByText('TEST-title');await act(async () => {});
  expect(screen.queryByRole('button', { name: 'Prepare again from the ticket' })).not.toBeInTheDocument();
});

// A build belonging to an OLDER generation must not hide it: that item was re-admitted and is starting over.
it('still offers to compose again when the build belonged to an earlier generation', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'awaiting_input', preparation: prepared(),
    builds: [{ attemptId: 'TEST-attempt', state: 'held', runId: 'TEST-run', reason: null, generation: detail().generation - 1 }] });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();

  expect(await screen.findByRole('button', { name: 'Prepare again from the ticket' })).toBeInTheDocument();
});

// The factory writes a generated item's specification and plan, and the server refuses to copy its ticket instead.
it('does not offer to compose a generated item from its ticket', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'awaiting_input', generated: true, builds: [] });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail();

  await screen.findByText('TEST-title');await act(async () => {});
  expect(screen.queryByRole('button', { name: 'Prepare again from the ticket' })).not.toBeInTheDocument();
});

it('offers composing again while a plan decision is open', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), workflowStatus: 'waiting_approval', preparation: prepared() });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
  const compose = vi.spyOn(preparationApi, 'composePreparation').mockResolvedValue({ reason: 'approval_required' });
  showDetail();

  fireEvent.click(await screen.findByRole('button', { name: 'Prepare again from the ticket' }));
  await waitFor(() => expect(compose).toHaveBeenCalledWith(detail().id, detail().revision));
});

// Live-data step 5: the screens follow the work-item change feed instead of a timer.
const SIGNED_IN: auth.Me = { authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] };
const itemWith = (reason: string): Detail => ({ ...detail(), events: [{ sequence: 1, type: 'Admitted', reason, occurredAt: item().updatedAt }] });
const listOf = (...rows: WorkItemSummary[]) => ({ items: rows, total: rows.length, offset: 0, limit: 50 });
function trackerAnswers() {
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-title', body: 'TEST-body', trackerStatus: 'open' });
}
/** The feed drops while the session is fine, so the hook reports it down and reconnects after 1.5 s. */
async function dropFeed() {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue(SIGNED_IN);
  vi.spyOn(auth, 'ensureServiceSessions').mockResolvedValue(false);
  await act(async () => FakeSocket.latest.onclose?.({ code: 1006, reason: '' }));
}
async function showTitledDetail() {
  showDetail();
  await screen.findByRole('heading', { level: 2, name: 'TEST-title' });
}

it('re-reads the list when the feed names a change, without blanking the rows', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue(listOf(item()));
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  await screen.findByText('TEST-0');
  FakeSocket.open();
  await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(2));
  FakeSocket.push({ changed: ['TEST-item-0'] });
  await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(3));
  expect(screen.getByText('TEST-0')).toBeInTheDocument();
  expect(screen.getByText(/^Updated /)).toBeInTheDocument();
});

// A socket that was down missed its notices, so every open reads the list again.
it('re-reads the list when the feed reconnects', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue(listOf(item()));
  vi.useFakeTimers({ shouldAdvanceTime: true });
  try {
    render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
    await screen.findByText('TEST-0');
    FakeSocket.open();
    await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(2));
    await dropFeed();
    await act(async () => { vi.advanceTimersByTime(1500); });
    expect(FakeSocket.sockets).toHaveLength(2);
    FakeSocket.open();
    await waitFor(() => expect(api.getWorkItems).toHaveBeenCalledTimes(3));
  } finally { vi.useRealTimers(); }
});

it('never reads the list on a timer', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue(listOf(item()));
  vi.useFakeTimers({ shouldAdvanceTime: true });
  try {
    render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
    await screen.findByText('TEST-0');
    await act(async () => { vi.advanceTimersByTime(60_000); });
    expect(api.getWorkItems).toHaveBeenCalledTimes(1);
  } finally { vi.useRealTimers(); }
});

// With no poll behind it, a dropped feed would leave old rows looking current.
it('says the list is not live while the feed is down', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue(listOf(item()));
  render(<MemoryRouter><WorkItems /></MemoryRouter>, { wrapper: queryWrapper() });
  await screen.findByText('TEST-0');
  FakeSocket.open();
  await dropFeed();
  expect(await screen.findByText('Live updates paused')).toBeInTheDocument();
});

it('reads the item again when the feed names it', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  trackerAnswers();
  await showTitledDetail();
  FakeSocket.open();
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(2));
  FakeSocket.push({ changed: ['TEST-item-0'] });
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(3));
});

it('ignores a notice for another item', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  trackerAnswers();
  await showTitledDetail();
  FakeSocket.open();
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(2));
  FakeSocket.push({ changed: ['TEST-item-9'] });
  await act(async () => {});
  expect(api.getWorkItem).toHaveBeenCalledTimes(2);
});

// The ticket text comes from the forge, which no database notice covers; reading it on every notice spends rate limit.
it('does not ask the tracker again on a notice', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  trackerAnswers();
  await showTitledDetail();
  FakeSocket.open();
  FakeSocket.push({ changed: '*' });
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(3));
  expect(api.getWorkItemTracker).toHaveBeenCalledTimes(1);
});

// A build writes several tables in a row; a slow read must still answer, and the change it may predate is read after.
it('reads the item once more after a burst, never two at once', async () => {
  const slow = deferred<Detail>();
  vi.spyOn(api, 'getWorkItem').mockResolvedValueOnce(itemWith('TEST-first read')).mockReturnValueOnce(slow.promise)
    .mockResolvedValue(itemWith('TEST-newest read'));
  trackerAnswers();
  showDetail();
  expect(await screen.findByText(/TEST-first read/)).toBeInTheDocument();
  FakeSocket.open();
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(2));
  FakeSocket.push({ changed: ['TEST-item-0'] });
  FakeSocket.push({ changed: ['TEST-item-0'] });
  FakeSocket.push({ changed: ['TEST-item-0'] });
  expect(api.getWorkItem).toHaveBeenCalledTimes(2);
  await act(async () => slow.resolve(itemWith('TEST-slow read')));
  expect(await screen.findByText(/TEST-newest read/)).toBeInTheDocument();
  expect(api.getWorkItem).toHaveBeenCalledTimes(3);
});

// A failed read is said beside the item it could not refresh, so stale data is never shown as current (PR 349).
it('shows a failed read beside the item until a later read succeeds', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValueOnce(itemWith('TEST-first read'))
    .mockRejectedValueOnce(new Error('TEST-read failed')).mockResolvedValue(itemWith('TEST-later read'));
  trackerAnswers();
  showDetail();
  expect(await screen.findByText(/TEST-first read/)).toBeInTheDocument();
  FakeSocket.open();
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-read failed');
  expect(screen.getByText(/TEST-first read/)).toBeInTheDocument();
  FakeSocket.push({ changed: ['TEST-item-0'] });
  expect(await screen.findByText(/TEST-later read/)).toBeInTheDocument();
  expect(screen.queryByRole('alert')).toBeNull();
});

// A first load that fails must not leave the page without the item for good (review of PR #179).
it('shows the item after a failed first load once the feed opens', async () => {
  vi.spyOn(api, 'getWorkItem').mockRejectedValueOnce(new Error('TEST-first load failed')).mockResolvedValue(itemWith('TEST-later read'));
  trackerAnswers();
  showDetail();
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-first load failed');
  FakeSocket.open();
  expect(await screen.findByText(/TEST-later read/)).toBeInTheDocument();
  expect(screen.queryByRole('alert')).toBeNull();
});

// A re-read under a decision the operator is reading would move what they approve; what changed meanwhile is read
// when the panel closes, because the socket that reopens reads again.
it('reads nothing while a panel is open, and reads again when it closes', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue(SIGNED_IN);
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), reason: 'specification_required' });
  trackerAnswers();
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0?prepare=1']}><Routes><Route path="/work-items/:id" element={<WorkItemDetail />} /></Routes></MemoryRouter>, { wrapper: queryWrapper() });
  const panel = await screen.findByRole('dialog', { name: 'Prepare the task' });
  expect(FakeSocket.sockets).toHaveLength(0);
  const reads = vi.mocked(api.getWorkItem).mock.calls.length;
  fireEvent.click(within(panel).getAllByRole('button', { name: 'Close' })[0]);
  await waitFor(() => expect(FakeSocket.sockets).toHaveLength(1));
  FakeSocket.open();
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(reads + 1));
});

it('says the item is not live while the feed is down', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  trackerAnswers();
  await showTitledDetail();
  FakeSocket.open();
  await dropFeed();
  expect(await screen.findByText('Live updates paused')).toBeInTheDocument();
});

it('never reads the item on a timer', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue(detail());
  trackerAnswers();
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  vi.useFakeTimers({ shouldAdvanceTime: true });
  try {
    await showTitledDetail();
    await act(async () => { vi.advanceTimersByTime(60_000); });
    expect(api.getWorkItem).toHaveBeenCalledTimes(1);
  } finally { vi.useRealTimers(); }
});

// Review of PR 356, M-1: closing the feed stops new notices, but a read already out still lands. It must not remount
// the form the operator is filling in.
it('keeps what the operator typed when a read lands under the preparation panel', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue(SIGNED_IN);
  const ready: Detail = { ...detail(), reason: 'specification_required' };
  const slow = deferred<Detail>();
  vi.spyOn(api, 'getWorkItem').mockResolvedValueOnce(ready).mockReturnValueOnce(slow.promise).mockResolvedValue({ ...ready, revision: 3 });
  trackerAnswers();
  await showTitledDetail();
  FakeSocket.open();
  await waitFor(() => expect(api.getWorkItem).toHaveBeenCalledTimes(2));
  fireEvent.click(screen.getByRole('button', { name: 'Prepare the task' }));
  fireEvent.change(await screen.findByRole('textbox', { name: 'Specification ticket' }), { target: { value: 'TEST-81' } });
  await act(async () => slow.resolve({ ...ready, revision: 2 }));
  expect(screen.getByRole('textbox', { name: 'Specification ticket' })).toHaveValue('TEST-81');
});

// Review of PR 356, M-2: with the feed down, Refresh workflow is how the plan steps catch up with the item.
it('reads the plan steps again on Refresh workflow', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), phase: 'build', workflowStatus: 'active', reason: 'phase_started',
    preparation: { ...prepared(), bindingVersion: 6 } });
  trackerAnswers();
  const steps = vi.spyOn(preparationApi, 'workSteps').mockResolvedValue({ rows: [] });
  await showTitledDetail();
  await waitFor(() => expect(steps).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('button', { name: 'Refresh workflow' }));
  await waitFor(() => expect(steps).toHaveBeenCalledTimes(2));
});
