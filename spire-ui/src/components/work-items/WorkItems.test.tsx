import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import * as api from '../../api';
import type { WorkItemDetail as Detail, WorkItemSummary } from '../../api';
import WorkItems from './WorkItems';
import WorkItemDetail from './WorkItemDetail';

afterEach(cleanup);

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
function showDetail() {
  render(<MemoryRouter initialEntries={['/work-items/TEST-item-0']}><Link to="/work-items/TEST-item-1">TEST-next item</Link><Routes>
    <Route path="/work-items/:id" element={<WorkItemDetail />} />
  </Routes></MemoryRouter>);
}

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
  fireEvent.click(screen.getByRole('button', { name: 'Refresh work items' }));
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
  fireEvent.click(screen.getByRole('button', { name: 'Refresh work items' }));
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
  await screen.findByRole('heading', { name: 'TEST-1' });
  await act(async () => old.resolve(detail()));
  expect(screen.getByRole('heading', { name: 'TEST-1' })).toBeInTheDocument();
});

it('ignores an older workflow error after navigation', async () => {
  const old = deferred<Detail>();
  vi.spyOn(api, 'getWorkItem').mockReturnValueOnce(old.promise).mockResolvedValue({ ...detail(), ...item(1) });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-current title', body: 'TEST-body', trackerStatus: 'open' });
  showDetail(); fireEvent.click(screen.getByRole('link', { name: 'TEST-next item' }));
  await screen.findByRole('heading', { name: 'TEST-1' });
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
  expect(api.getWorkItems).toHaveBeenLastCalledWith(50, 50);
  expect(screen.queryByText('TEST-0')).toBeNull();
  expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Previous page' }));
  await screen.findByText('TEST-0');
  expect(api.getWorkItems).toHaveBeenLastCalledWith(0, 50);
});

it('renders an unknown workflow status as unknown and refused', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [{ ...item(), workflowStatus: 'TEST-future' }], total: 1, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  expect(await screen.findByText('Unknown (TEST-future)')).toHaveClass('refused');
  expect(screen.queryByText('Completed')).toBeNull();
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
  expect(screen.getByText(/actor 900123, audit trail/)).toBeInTheDocument();
});

it('shows the ignored reason and no selected profile', async () => {
  vi.spyOn(api, 'getWorkItem').mockResolvedValue({ ...detail(), profile: null, workflowStatus: 'not_eligible',
    ignoredLabels: [{ label: 'TEST-autonomous', reason: 'Current label applier is unattributed.', actorId: '900123', origin: 'UNATTRIBUTED' }] });
  vi.spyOn(api, 'getWorkItemTracker').mockResolvedValue({ title: 'TEST-live title', body: 'TEST-live body', trackerStatus: 'open' });
  showDetail();
  await screen.findByText('Not eligible');
  expect(screen.getByText('No profile selected')).toBeInTheDocument();
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
