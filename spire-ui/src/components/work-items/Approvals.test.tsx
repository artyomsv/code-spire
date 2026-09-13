import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import * as auth from '../../auth';
import * as api from './approvalsApi';
import Approvals from './Approvals';

const row: api.Approval = { workItemId: 'TEST-item', issueKey: 'TEST-42', gate: { id: 'TEST-gate', version: 7, state: 'OPEN', phase: 'plan', generation: 2,
  itemRevision: 11, policyRevision: 3, artifact: 'TEST-sha256', openedAt: '2026-09-13T12:00:00Z', expiresAt: '2026-09-14T12:00:00Z', resolver: null, channel: null, note: null } };
beforeEach(() => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'approvals').mockResolvedValue([row]);
  vi.spyOn(api, 'answer').mockResolvedValue();
});
function show() { render(<MemoryRouter><Approvals /></MemoryRouter>); }
it('submits the displayed gate version and removes a resolved open row', async () => {
  show(); fireEvent.change(await screen.findByLabelText('Decision note'), { target: { value: 'TEST-reviewed' } });
  vi.mocked(api.approvals).mockResolvedValue([]); fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
  await screen.findByText('No open approvals.');
  expect(api.answer).toHaveBeenCalledWith(row.gate, expect.any(String), true, 'TEST-reviewed');
  expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
});
it('keeps the decision visible after a failed answer', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-409 decision changed'));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-409 decision changed');
  expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
  expect(screen.getByText('TEST-42')).toBeInTheDocument();
});
it('reuses an answer identity after transport failure but replaces it for a different decision', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-timeout'));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Approve' })); await screen.findByRole('alert');
  const first = vi.mocked(api.answer).mock.calls[0][1];
  fireEvent.click(screen.getByRole('button', { name: 'Approve' })); await screen.findByRole('alert');
  expect(vi.mocked(api.answer).mock.calls[1][1]).toBe(first);
  fireEvent.click(screen.getByRole('button', { name: 'Reject' })); await screen.findByRole('alert');
  expect(vi.mocked(api.answer).mock.calls[2][1]).not.toBe(first);
});
it('gives an edited note a new answer identity', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-timeout'));
  show();fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));await screen.findByRole('alert');
  const first = vi.mocked(api.answer).mock.calls[0][1];
  fireEvent.change(screen.getByLabelText('Decision note'), { target: { value: 'TEST-revised note' } });
  fireEvent.click(screen.getByRole('button', { name: 'Approve' }));await screen.findByRole('alert');
  expect(vi.mocked(api.answer).mock.calls[1][1]).not.toBe(first);
});
it('disables a second submission while the first answer is pending', async () => {
  let resolve!: () => void;
  vi.mocked(api.answer).mockReturnValue(new Promise(done => { resolve = done; }));
  show();fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();expect(api.answer).toHaveBeenCalledTimes(1);
  await act(async () => { resolve(); });
});
it('never offers answer controls to a viewer', async () => {
  vi.mocked(auth.fetchMe).mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  show(); await screen.findByText('TEST-42'); await act(async () => {});
  expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
});
it('shows history without offering answers to a closed gate', async () => {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, state: 'EXPIRED' } }]);
  show(); await screen.findByText('TEST-42'); await act(async () => {});
  expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByLabelText('Show decision history'));
  await waitFor(() => expect(api.approvals).toHaveBeenLastCalledWith(true));
});
it('ignores an old open-list response after selecting history', async () => {
  let resolve!: (rows: api.Approval[]) => void;
  vi.mocked(api.approvals).mockReturnValueOnce(new Promise(done => { resolve = done; })).mockResolvedValue([]);
  show(); fireEvent.click(screen.getByLabelText('Show decision history')); await screen.findByText('No past decisions.');
  await act(async () => { resolve([row]); });
  expect(screen.queryByText('TEST-42')).not.toBeInTheDocument();
});
