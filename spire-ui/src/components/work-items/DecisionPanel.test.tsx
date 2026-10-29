import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as gateway from '../../api';
import * as auth from '../../auth';
import * as api from './approvalsApi';
import * as preparation from './workPreparationApi';
import * as draft from './workDraftApi';
import DecisionPanel from './DecisionPanel';
import PastDecisions from './PastDecisions';
import { workKeys } from './workQueries';
import { createQueryClient } from '../../queryClient';
import { queryWrapper } from '../../test/queryWrapper';

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
const evidence: preparation.PreparationEvidence = { reason: null, detail: null, specification: 'TEST-specification text', instruction: 'TEST-the one step', binding: row.gate.artifact! };
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
/** Approve is offered only once the bound texts are on screen; a test that approves waits for that. */
async function approvable() {
  const button = await screen.findByRole('button', { name: 'Approve' });
  await waitFor(() => expect(button).toBeEnabled());
  return button;
}
function show() { return render(<MemoryRouter><DecisionPanel itemId="TEST-item" title="TEST-ticket title" onClose={vi.fn()} onDecided={decided} /></MemoryRouter>, { wrapper: queryWrapper() }); }

// How it pays is part of what the gate binds, and which text is which was not said (feedback, 2026-09-27).
it('shows how the build pays and labels the specification and the plan', async () => {
  vi.mocked(gateway.getWorkItem).mockResolvedValue({ ...item, preparation: { ...item.preparation, payWith: 'SUBSCRIPTION' } });
  show();
  expect(await screen.findByText('Specification — the ticket text, as it was prepared')).toBeInTheDocument();
  expect(screen.getByText('Plan — the one step the build runs')).toBeInTheDocument();
  expect(screen.getByText('a Codex subscription')).toBeInTheDocument();
});

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
  show(); fireEvent.click(await approvable());
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-409 decision changed');
  expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
  expect(decided).not.toHaveBeenCalled();
});
it('reuses an answer identity after transport failure but replaces it for a different decision', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-timeout'));
  show(); fireEvent.click(await approvable()); await screen.findByRole('alert');
  const first = vi.mocked(api.answer).mock.calls[0][1];
  fireEvent.click(screen.getByRole('button', { name: 'Approve' })); await waitFor(() => expect(api.answer).toHaveBeenCalledTimes(2));
  await screen.findByRole('alert');
  expect(vi.mocked(api.answer).mock.calls[1][1]).toBe(first);
  fireEvent.click(screen.getByRole('button', { name: 'Reject' })); await waitFor(() => expect(api.answer).toHaveBeenCalledTimes(3));
  expect(vi.mocked(api.answer).mock.calls[2][1]).not.toBe(first);
});
it('gives an edited note a new answer identity', async () => {
  vi.mocked(api.answer).mockRejectedValue(new Error('TEST-timeout'));
  show(); fireEvent.click(await approvable()); await screen.findByRole('alert');
  const first = vi.mocked(api.answer).mock.calls[0][1];
  fireEvent.change(screen.getByLabelText('Decision note', { selector: 'textarea' }), { target: { value: 'TEST-revised note' } });
  fireEvent.click(screen.getByRole('button', { name: 'Approve' })); await waitFor(() => expect(api.answer).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.answer).mock.calls[1][1]).not.toBe(first);
});
// A locked panel whose buttons keep their names looks like a click that did nothing.
it('names the answer it is recording and locks every control until the server replies', async () => {
  let release!: () => void;
  vi.mocked(api.answer).mockReturnValue(new Promise<void>(resolve => { release = resolve; }));
  show(); fireEvent.click(await approvable());
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
  show(); fireEvent.click(await approvable());
  expect(await screen.findByRole('alert')).toHaveTextContent('The decision changed. The specification ticket changed after it was checked.');
});

it('lists past decisions with who answered them', async () => {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, state: 'APPROVED', resolver: 'TEST-admin', channel: 'dashboard' } }]);
  render(<MemoryRouter><PastDecisions onClose={vi.fn()} /></MemoryRouter>, { wrapper: queryWrapper() });
  expect(await screen.findByRole('link', { name: 'TEST-42' })).toHaveAttribute('href', '/work-items/TEST-item');
  expect(screen.getByText(/by TEST-admin via dashboard/)).toBeInTheDocument();
  expect(api.approvals).toHaveBeenCalledWith(true);
});
it('says when no decision is closed yet', async () => {
  vi.mocked(api.approvals).mockResolvedValue([]);
  render(<MemoryRouter><PastDecisions onClose={vi.fn()} /></MemoryRouter>, { wrapper: queryWrapper() });
  expect(await screen.findByText(/No decision has been answered/)).toBeInTheDocument();
});

// Review finding: Approve was offered before, and without, the texts it promises to show.
it('offers Approve only once the bound texts are on screen, and keeps Reject', async () => {
  let release!: (value: preparation.PreparationEvidence) => void;
  vi.mocked(preparation.preparationEvidence).mockReturnValue(new Promise(resolve => { release = resolve; }));
  show();
  expect(await screen.findByRole('button', { name: 'Approve' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Reject' })).toBeEnabled();
  expect(screen.getByText(/Reading the tickets/)).toBeInTheDocument();
  await act(async () => release(evidence));
  expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
});
it('keeps Approve unavailable when the tickets cannot be read or have moved', async () => {
  vi.mocked(preparation.preparationEvidence).mockRejectedValue(new Error('TEST-tracker down'));
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-tracker down');
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
  cleanup();
  vi.mocked(preparation.preparationEvidence).mockResolvedValue({ ...evidence, reason: 'artifacts_changed', detail: 'plan_changed', specification: null, instruction: null });
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('The plan ticket changed');
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
});
it('refuses to show texts read for another preparation than the decision binds', async () => {
  vi.mocked(preparation.preparationEvidence).mockResolvedValue({ ...evidence, binding: 'TEST-a newer binding', instruction: 'TEST-a newer step' });
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('The prepared task changed after this decision was opened');
  expect(screen.queryByText('TEST-a newer step')).toBeNull();
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
});

// Review finding: a server that does not report the binding left Approve off with a message that did not say why.
it('says the server must report the binding when it does not', async () => {
  vi.mocked(preparation.preparationEvidence).mockResolvedValue({ ...evidence, binding: undefined });
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('The server does not say which prepared version');
  expect(screen.queryByText('TEST-the one step')).toBeNull();
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
});
// M4 land (spec §7.10): before approval the land gate says what approving will do.
it('the land gate says what will happen', async () => {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, phase: 'land', artifact: 'b'.repeat(40) } }]);
  const pullRequest = { number: 901, url: 'https://TEST.example/pull/901', draft: false };
  vi.mocked(gateway.getWorkItem).mockResolvedValue({ ...item, phase: 'land', progress: { execution: { runId: 'TEST-run',
    build: { workItemId: 'TEST-item', generation: 2, buildAttemptId: 'TEST-attempt', preparationBinding: 'TEST-sha256' }, head: 'b'.repeat(40),
    verificationAttempt: 'TEST-verify', pullRequest, reviewId: 'TEST-review' } },
    land: { state: 'handed', reason: 'land_blocked', message: null, method: 'squash', ticketRule: 'close', branchDeleted: null, note: null,
      checks: { state: 'FAILED', done: 3, total: 4, failed: [{ name: 'TEST-ci', conclusion: 'failure', title: 'TEST-title', summary: 'TEST-summary', annotations: [] }] },
      attempt: 0, attemptsAllowed: 3, fixInProgress: false, handedOver: true, stoppable: true } });
  show();
  const preview = await screen.findByRole('region', { name: 'What land will do' });
  expect(preview).toHaveTextContent('Merges pull request #901 with a squash merge');
  expect(preview).toHaveTextContent('deletes its branch');
  expect(preview).toHaveTextContent('closes the ticket');
  expect(preview).toHaveTextContent('3 of 4 checks done');
  expect(preview).toHaveTextContent('TEST-ci');
});
// A land decision binds a built commit, not the task texts, so it must not wait for them.
it('does not hold a land decision back on the task texts', async () => {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, phase: 'land', artifact: 'b'.repeat(40) } }]);
  vi.mocked(preparation.preparationEvidence).mockReturnValue(new Promise(() => {}));
  show();
  await approvable();
  expect(screen.queryByText(/Reading the tickets/)).toBeNull();
});

// Review finding: a decision bound to a preparation the item no longer has skipped the text check entirely.
it('offers no Approve for a decision whose prepared task the item no longer has', async () => {
  vi.mocked(gateway.getWorkItem).mockResolvedValue({ ...item, preparation: null });
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('binds a prepared task the item no longer has');
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Reject' })).toBeEnabled();
});

const resultGateRow = { ...row, gate: { ...row.gate, phase: 'verify' } };
const failedItem = { ...item, phase: 'verify', reason: 'verify_failed', gate: resultGateRow.gate };
it('a verify result gate offers Retry build and Stop', async () => {
  vi.mocked(api.approvals).mockResolvedValue([resultGateRow]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue(failedItem);
  show();
  expect(await screen.findByRole('button', { name: 'Retry build' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
});
// Review of PR #184 (I4): a retry after an unreadable checkpoint starts from the base, and the panel says so.
it('says a retry starts from the base when the checkpoint could not be read', async () => {
  const missing = { ...failedItem, reason: 'verify_unverified', progress: { execution: { verification: {
    attemptId: 'TEST-attempt', head: 'a'.repeat(40), outcome: 'UNVERIFIED', reason: 'checkpoint_missing', checks: [] } } } } as unknown as typeof failedItem;
  vi.mocked(api.approvals).mockResolvedValue([resultGateRow]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue(missing);
  show();
  expect(await screen.findByText(/starts again from the base/)).toBeInTheDocument();
  expect(screen.queryByText(/starts from this build's last commit/)).toBeNull();
});
// M4 slice 3, decision 7: a later plan step's run failed before verify; Retry builds that step again from its start.
it('says a failed plan step builds again from where it started', async () => {
  const stepFailed = { ...failedItem, reason: 'verify_unverified', progress: { execution: { verification: {
    attemptId: 'TEST-attempt', head: 'a'.repeat(40), outcome: 'UNVERIFIED', reason: 'step_run_failed', checks: [] } } } } as unknown as typeof failedItem;
  vi.mocked(api.approvals).mockResolvedValue([resultGateRow]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue(stepFailed);
  show();
  // The reason's sentence also shows with the result; the approve section is where Retry is explained.
  const approve = await screen.findByRole('region', { name: 'If you approve' });
  expect(within(approve).getByText(/builds this step again from where it started/)).toBeInTheDocument();
  expect(screen.queryByText(/starts again from the base/)).toBeNull();
});
// Decision 11: a plan step's commits are gone and never rebased on the base, so the way on is Readmit.
it('says a plan step whose commits are gone needs a readmit', async () => {
  const lost = { ...failedItem, reason: 'verify_unverified', preparation: { ...item.preparation, bindingVersion: 6 },
    progress: { execution: { verification: {
      attemptId: 'TEST-attempt', head: 'a'.repeat(40), outcome: 'UNVERIFIED', reason: 'checkpoint_missing', checks: [] } } } } as unknown as typeof failedItem;
  vi.mocked(api.approvals).mockResolvedValue([resultGateRow]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue(lost);
  show();
  expect(await screen.findByText(/Readmit builds the plan again from step 1/)).toBeInTheDocument();
  expect(screen.queryByText(/starts again from the base/)).toBeNull();
});
it('Retry build answers approve, and Stop answers reject', async () => {
  vi.mocked(api.approvals).mockResolvedValue([resultGateRow]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue(failedItem);
  show();
  await waitFor(() => expect(screen.getByRole('button', { name: 'Retry build' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'Retry build' }));
  await waitFor(() => expect(decided).toHaveBeenCalledWith('Retrying the build.'));
  expect(api.answer).toHaveBeenLastCalledWith(resultGateRow.gate, expect.any(String), true, expect.any(String));
  cleanup();
  show();
  fireEvent.click(await screen.findByRole('button', { name: 'Stop' }));
  await waitFor(() => expect(decided).toHaveBeenCalledWith('Stopped after verification.'));
  expect(api.answer).toHaveBeenLastCalledWith(resultGateRow.gate, expect.any(String), false, expect.any(String));
});

// A generated item's spec gate asks about the written specification (M4 slice 3, A6), so that text is the evidence.
const writtenSha = 'b'.repeat(64);
function specGate(sha256: string) {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, phase: 'spec', artifact: writtenSha } }]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue({ ...item, phase: 'spec', reason: 'specification_written', preparation: null });
  vi.spyOn(draft, 'workDraft').mockResolvedValue({ specification: { text: '## Outcome\nTEST-written outcome', sha256, comment: { state: 'sent', ref: null } },
    plan: null, revisions: { specification: 0, plan: 0 }, ticketLink: 'https://TEST.example/issues/42' });
}
it('a spec gate shows the specification as evidence', async () => {
  specGate(writtenSha);
  show();
  expect(await screen.findByLabelText('Specification text')).toHaveTextContent('TEST-written outcome');
  expect(await approvable()).toBeEnabled();
  expect(screen.queryByText(/binds a prepared task the item no longer has/)).toBeNull();
});
it('a spec gate on another specification is not approvable', async () => {
  specGate('c'.repeat(64));
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('The specification changed after this decision was opened');
  expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
});

// A generated item's plan gate asks about the written plan (M4 slice 3, A6), and offers Revise beside Approve and Reject.
const planSha = 'd'.repeat(64);
function planGate(revisions: number) {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, phase: 'plan', artifact: planSha } }]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue({ ...item, phase: 'plan', reason: 'plan_written', preparation: null });
  vi.spyOn(draft, 'workDraft').mockResolvedValue({
    specification: { text: '## Outcome\nTEST-written outcome', sha256: writtenSha, comment: null },
    plan: { text: '{}', sha256: planSha, comment: null, steps: [{ id: 'TEST-s1', title: 'TEST-step-1', instruction: 'TEST-instruction', doneWhen: 'TEST-done-1' }] },
    revisions: { specification: 0, plan: revisions }, ticketLink: 'https://TEST.example/issues/42' });
  vi.spyOn(api, 'revise').mockResolvedValue();
}
it('a plan gate shows the steps as evidence', async () => {
  planGate(0);
  show();
  expect(await screen.findByRole('list', { name: 'Plan steps' })).toHaveTextContent('TEST-step-1');
  expect(await approvable()).toBeEnabled();
});
it('Revise needs a note and hides after three', async () => {
  planGate(1);
  show();
  const revise = await screen.findByRole('button', { name: 'Revise with a note' });
  expect(revise).toBeDisabled();
  expect(screen.getByText('1 of 3 revisions used')).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Decision note', { selector: 'textarea' }), { target: { value: 'TEST-split the step' } });
  expect(revise).toBeEnabled();
  fireEvent.click(revise);
  await waitFor(() => expect(decided).toHaveBeenCalledWith('Asked for a new plan. The factory writes it with your note.'));
  expect(api.revise).toHaveBeenCalledWith(expect.objectContaining({ id: row.gate.id, phase: 'plan' }), expect.any(String), 'TEST-split the step');
  cleanup();

  planGate(3);
  show();
  expect(await screen.findByText(/All 3 revisions are used/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Revise with a note' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
});
it('offers no Revise on a permission decision', async () => {
  show();
  await approvable();
  expect(screen.queryByRole('button', { name: 'Revise with a note' })).toBeNull();
});

// Step 6: the panel reads the item under the key the detail page keeps it under, so it opens on what is on screen.
it('shares the item read with the screen behind it', async () => {
  vi.mocked(gateway.getWorkItem).mockReturnValue(new Promise(() => {}));
  const client = createQueryClient();
  client.setQueryData(workKeys.item('TEST-item'), item);
  render(<QueryClientProvider client={client}><MemoryRouter>
    <DecisionPanel itemId="TEST-item" title="TEST-ticket title" onClose={vi.fn()} onDecided={decided} /></MemoryRouter></QueryClientProvider>);
  expect(await screen.findByRole('dialog', { name: 'Approve the plan' })).toBeInTheDocument();
});
