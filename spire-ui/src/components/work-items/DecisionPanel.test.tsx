import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as gateway from '../../api';
import * as auth from '../../auth';
import * as api from './approvalsApi';
import * as preparation from './workPreparationApi';
import DecisionPanel from './DecisionPanel';
import PastDecisions from './PastDecisions';

const artifact = (key: string): preparation.Artifact => ({ sha256: key.repeat(64).slice(0, 64),
  location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: `TEST-${key}` }, issueKey: key, link: `https://TEST.example/issues/${key}` } });
const row: api.Approval = { workItemId: 'TEST-item', issueKey: 'TEST-42', trackerCommand: '/approve TEST-gate 2 TEST-artifact', gate: { id: 'TEST-gate', version: 7, state: 'OPEN', phase: 'plan', generation: 2,
  itemRevision: 11, policyRevision: 3, artifact: 'TEST-sha256', openedAt: '2026-09-13T12:00:00Z', expiresAt: '2999-09-14T12:00:00Z', resolver: null, channel: null, note: null } };
const item = { id: 'TEST-item', sourceId: 'TEST-source', repositoryId: 'TEST-repository', repository: 'TEST-owner/TEST-repo', issueKey: 'TEST-42',
  trackerUrl: 'https://TEST.example/issues/42', generation: 2, phase: 'plan', workflowStatus: 'waiting_approval', reason: 'approval_required', profile: null,
  revision: 11, updatedAt: '2026-09-13T12:00:00Z', effectiveModes: {}, admittedModes: {}, policyReason: 'policy_selected', ceiling: null, appliedLabels: [], ignoredLabels: [], events: [],
  effectiveLimits: { gateTtlSeconds: 86400, maxRunsPerItem: 5, maxStepsPerPlan: 20, maxWallClockSeconds: 7200, maxCostMillicents: 2_000_000, maxCallsPerItem: 40, protectedPaths: [] },
  preparation: { specification: artifact('71'), plan: artifact('72'), baseBranch: 'main', baseCommit: 'a0f8a41'.padEnd(40, '0'), harness: 'TEST-harness', model: 'TEST-model', registeredBy: 'TEST-operator' },
} satisfies gateway.WorkItemDetail;
const evidence: preparation.PreparationEvidence = { reason: null, detail: null, specification: 'TEST-specification text', instruction: 'TEST-the one step' };
const decided = vi.fn();

afterEach(cleanup);
beforeEach(() => {
  decided.mockReset();
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-admin', roles: ['spire-admin'] });
  vi.spyOn(api, 'approvals').mockResolvedValue([row]);
  vi.spyOn(api, 'answer').mockResolvedValue();
  vi.spyOn(gateway, 'getWorkItem').mockResolvedValue(item);
  vi.spyOn(preparation, 'preparationEvidence').mockResolvedValue(evidence);
});
function show() { return render(<MemoryRouter><DecisionPanel itemId="TEST-item" title="TEST-ticket title" onClose={vi.fn()} onDecided={decided} /></MemoryRouter>); }

// The old card showed a digest and a generation number. An approver has to see what they approve.
it('shows what the gate binds before offering an answer', async () => {
  show();
  expect(await screen.findByText('TEST-the one step')).toBeInTheDocument();
  expect(screen.getByText('TEST-specification text')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '#71' })).toHaveAttribute('href', 'https://TEST.example/issues/71');
  expect(screen.getByText('main @ a0f8a41')).toBeInTheDocument();
  expect(screen.getByText('TEST-harness · TEST-model')).toBeInTheDocument();
  expect(screen.getByRole('region', { name: 'If you approve' })).toHaveTextContent('One build starts. It stops at $20.000 or after 2 hours, and this item may use up to 5 runs.');
  expect(screen.getByText('TEST-ticket title · TEST-42 · TEST-owner/TEST-repo')).toBeInTheDocument();
});
it('names a moved ticket instead of showing its unapproved text', async () => {
  vi.mocked(preparation.preparationEvidence).mockResolvedValue({ reason: 'artifacts_changed', detail: 'plan_changed', specification: null, instruction: null });
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('The plan ticket changed after it was checked.');
  expect(screen.queryByLabelText('The step the build runs')).toBeNull();
});
it('says so when the item has no open decision', async () => {
  vi.mocked(api.approvals).mockResolvedValue([]);
  show();
  expect(await screen.findByText(/has no open decision/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
});
it('submits the displayed gate version and reports the decision', async () => {
  show(); fireEvent.change(await screen.findByLabelText('Decision note', { selector: 'textarea' }), { target: { value: 'TEST-reviewed' } });
  fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
  await waitFor(() => expect(decided).toHaveBeenCalledWith('Approved the plan decision. The item continues.'));
  expect(api.answer).toHaveBeenCalledWith(row.gate, expect.any(String), true, 'TEST-reviewed');
});
it('keeps the decision open after a failed answer', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-409 decision changed'));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-409 decision changed');
  expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
  expect(decided).not.toHaveBeenCalled();
});
it('reuses an answer identity after transport failure but replaces it for a different decision', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-timeout'));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Approve' })); await screen.findByRole('alert');
  const first = vi.mocked(api.answer).mock.calls[0][1];
  fireEvent.click(screen.getByRole('button', { name: 'Approve' })); await waitFor(() => expect(api.answer).toHaveBeenCalledTimes(2));
  await screen.findByRole('alert');
  expect(vi.mocked(api.answer).mock.calls[1][1]).toBe(first);
  fireEvent.click(screen.getByRole('button', { name: 'Reject' })); await waitFor(() => expect(api.answer).toHaveBeenCalledTimes(3));
  expect(vi.mocked(api.answer).mock.calls[2][1]).not.toBe(first);
});
it('gives an edited note a new answer identity', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-timeout'));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Approve' })); await screen.findByRole('alert');
  const first = vi.mocked(api.answer).mock.calls[0][1];
  fireEvent.change(screen.getByLabelText('Decision note', { selector: 'textarea' }), { target: { value: 'TEST-revised note' } });
  fireEvent.click(screen.getByRole('button', { name: 'Approve' })); await waitFor(() => expect(api.answer).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.answer).mock.calls[1][1]).not.toBe(first);
});
// A locked panel whose buttons keep their names looks like a click that did nothing.
it('names the answer it is recording and locks every control until the server replies', async () => {
  let release!: () => void;
  vi.mocked(api.answer).mockReturnValue(new Promise<void>(resolve => { release = resolve; }));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  expect(await screen.findByRole('button', { name: 'Approving…' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  // The header close sits outside the panel's fieldset; closing mid-answer hides an answer still landing.
  expect(screen.getByRole('button', { name: 'Close' })).toBeDisabled();
  expect(screen.getByText(/Recording your decision/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Approving…' }));
  expect(api.answer).toHaveBeenCalledTimes(1);
  await act(async () => { release(); });
});
it('names a rejection separately from an approval', async () => {
  let release!: () => void;
  vi.mocked(api.answer).mockReturnValue(new Promise<void>(resolve => { release = resolve; }));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
  expect(await screen.findByRole('button', { name: 'Rejecting…' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
  await act(async () => { release(); });
});
it('never offers answers or reads ticket bodies for a viewer', async () => {
  vi.mocked(auth.fetchMe).mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-viewer', roles: ['spire-viewer'] });
  show();
  expect(await screen.findByText('Only an administrator can answer this decision.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  expect(preparation.preparationEvidence).not.toHaveBeenCalled();
});
it('offers the tracker command, and mentions pull request reviews only for a land decision', async () => {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, prReviewAvailable: false, prReviewDetail: 'TEST-forge cannot prove current approval' }]);
  show();
  expect(await screen.findByText('/approve TEST-gate 2 TEST-artifact')).toBeInTheDocument();
  expect(screen.queryByText('TEST-forge cannot prove current approval')).toBeNull();
  cleanup();
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, phase: 'land' }, prReviewAvailable: false, prReviewDetail: 'TEST-forge cannot prove current approval' }]);
  show();
  expect(await screen.findByText('TEST-forge cannot prove current approval')).toBeInTheDocument();
});
// A superseded plan decision is answered with the ticket that moved; the approver re-reads that one.
it('names the ticket that moved when a decision is superseded', async () => {
  vi.mocked(api.answer).mockRestore();
  vi.spyOn(auth, 'apiFetch').mockResolvedValue(new Response(JSON.stringify({ reason: 'artifacts_changed_requires_new_decision', detail: 'specification_changed' }), { status: 409 }));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('The decision changed. The specification ticket changed after it was checked.');
});

it('lists past decisions with who answered them', async () => {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, state: 'APPROVED', resolver: 'TEST-admin', channel: 'dashboard' } }]);
  render(<MemoryRouter><PastDecisions onClose={vi.fn()} /></MemoryRouter>);
  expect(await screen.findByRole('link', { name: 'TEST-42' })).toHaveAttribute('href', '/work-items/TEST-item');
  expect(screen.getByText(/by TEST-admin via dashboard/)).toBeInTheDocument();
  expect(api.approvals).toHaveBeenCalledWith(true);
});
it('says when no decision is closed yet', async () => {
  vi.mocked(api.approvals).mockResolvedValue([]);
  render(<MemoryRouter><PastDecisions onClose={vi.fn()} /></MemoryRouter>);
  expect(await screen.findByText(/No decision has been answered/)).toBeInTheDocument();
});
