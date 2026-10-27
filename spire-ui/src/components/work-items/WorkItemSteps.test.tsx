import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import type { Gate } from './approvalsApi';
import WorkItemSteps from './WorkItemSteps';
import * as draftApi from './workDraftApi';
import type { WrittenPlan } from './workDraftApi';
import * as preparationApi from './workPreparationApi';
import type { StepRow, WorkExecution } from './workPreparationApi';

afterEach(cleanup);
const ASSISTED = { INTAKE: 'auto', SPEC: 'auto', PLAN: 'approve', BUILD: 'auto', VERIFY: 'auto', DELIVER: 'draft_pr', REVIEW: 'auto', LAND: 'approve' };
const gate: Gate = { id: 'TEST-plan-gate', version: 1, state: 'OPEN', phase: 'plan', generation: 1, itemRevision: 4, policyRevision: 1,
  artifact: 'TEST-digest', openedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-09-14T01:00:00Z', resolver: null, channel: null, note: null };
type Item = Parameters<typeof WorkItemSteps>[0]['item'];
function item(overrides: Partial<Item> = {}): Item {
  return { id: 'TEST-item', generation: 1, phase: 'plan', workflowStatus: 'waiting_approval', reason: 'approval_required', effectiveModes: ASSISTED,
    profile: { id: 'TEST-profile', name: 'TEST-assisted', version: 1 }, appliedLabels: [], ignoredLabels: [], people: [], builds: [], gate, events: [], ...overrides };
}
function show(value: Item, current = <button type="button">TEST-current action</button>) {
  return render(<MemoryRouter><WorkItemSteps item={value} current={current} /></MemoryRouter>);
}
const step = (name: string) => within(screen.getByRole('listitem', { name: new RegExp(`^Step \\d: ${name}$`) }));

it('marks the current step and keeps the actions inside it', () => {
  show(item());
  const plan = screen.getByRole('listitem', { name: 'Step 3: Plan' });
  expect(plan).toHaveAttribute('aria-current', 'step');
  expect(within(plan).getByText('An operator decision is required before this phase can start.')).toBeInTheDocument();
  expect(within(plan).getByRole('button', { name: 'TEST-current action' })).toBeInTheDocument();
  expect(within(plan).getByText('Waiting for the plan decision.')).toBeInTheDocument();
  expect(screen.getAllByRole('button', { name: 'TEST-current action' })).toHaveLength(1);
});
it('says who decides a later step and shows nothing else there', () => {
  show(item());
  expect(screen.getByRole('listitem', { name: 'Step 6: Deliver' })).toHaveTextContent('deliver · opens a draft pull request');
  expect(screen.getByRole('listitem', { name: 'Step 8: Land' })).toHaveTextContent('land · asks a person');
  expect(screen.getByRole('listitem', { name: 'Step 4: Build' })).not.toHaveTextContent('Runs recorded');
});
it('names the label applier by handle and keeps the id beside it', () => {
  show(item({ appliedLabels: [{ label: 'TEST-assisted', actorId: '900123', origin: 'AUDIT_TRAIL', eventId: 'TEST-event', profileId: 'TEST-profile', profileVersion: 1 }],
    people: [{ providerUserId: '900123', handle: 'TEST-person', displayName: null }] }));
  expect(step('Picked up').getByText(/added by @TEST-person/)).toHaveTextContent('900123, audit trail');
});
it('shows why an ignored ticket never started', () => {
  show(item({ phase: 'intake', workflowStatus: 'not_eligible', reason: 'no_eligible_label', profile: null, gate: null,
    ignoredLabels: [{ label: 'TEST-auto', reason: 'actor_not_allowed', actorId: '900456', origin: 'WEBHOOK' }] }));
  expect(step('Picked up').getByText('No profile selected')).toBeInTheDocument();
  expect(step('Picked up').getByText(/ignored: The person who applied this label is not on the source allowlist/)).toBeInTheDocument();
  expect(screen.getByRole('listitem', { name: 'Step 1: Picked up' })).toHaveClass('stopped');
});
// Suggest, assisted and autonomous differ in their workflow facts, not in a profile name.
it('shows a recorded decision and the run it started', () => {
  show(item({ phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: { ...gate, state: 'APPROVED', resolver: 'TEST-human' },
    builds: [{ attemptId: 'TEST-attempt', state: 'sent', runId: 'TEST-assisted-run', reason: null, generation: 1 }],
    events: [{ sequence: 5, type: 'GATE_RESOLVED', reason: 'approval_required', occurredAt: gate.openedAt, phase: 'plan', gateState: 'APPROVED', resolver: 'TEST-human', generation: 1 }] }));
  expect(step('Plan').getByText(/^plan decision: approved by TEST-human/)).toBeInTheDocument();
  expect(step('Plan').queryByText('Waiting for the plan decision.')).toBeNull();
  expect(step('Build').getByText('Runs recorded: 1')).toBeInTheDocument();
  expect(step('Build').getByRole('link', { name: 'Open run' })).toHaveAttribute('href', '/runs/TEST-assisted-run');
});
it('shows build evidence and the observed delivery state', () => {
  const execution: WorkExecution = { runId: 'TEST-run', build: { workItemId: 'TEST-item', generation: 1, buildAttemptId: 'TEST-attempt', preparationBinding: 'a'.repeat(64) },
    head: 'b'.repeat(40), verificationAttempt: null, pullRequest: null, reviewId: null };
  const base = item({ phase: 'verify', workflowStatus: 'capability_unavailable', reason: 'verify_capability_unavailable', gate: null, progress: { execution } });
  const { rerender } = show(base);
  expect(step('Build').getByText(/Built commit/)).toHaveTextContent(`${execution.head} · held, not pushed`);
  expect(step('Verify').getByText('Verification not recorded')).toBeInTheDocument();
  const pullRequest = { number: 901, url: 'https://forge.example.test/TEST-pull/901', draft: true };
  const delivered = { ...base, phase: 'land', workflowStatus: 'waiting_approval', progress: { execution: { ...execution, verificationAttempt: 'TEST-verification', pullRequest, reviewId: 'TEST-review' } } };
  rerender(<MemoryRouter><WorkItemSteps item={delivered} current={null} /></MemoryRouter>);
  expect(step('Verify').getByText('Verification recorded without a result')).toBeInTheDocument();
  expect(step('Deliver').getByRole('link', { name: 'Draft pull request #901' })).toHaveAttribute('href', pullRequest.url);
  expect(step('Review').getByText(/Review recorded for this build/)).toBeInTheDocument();
  expect(step('Review').queryByRole('link', { name: 'Open the review' })).toBeNull();
  rerender(<MemoryRouter><WorkItemSteps item={{ ...delivered, progress: { execution: { ...delivered.progress.execution, reviewId: 'review::TEST-owner/TEST-repo#901' } } }} current={null} /></MemoryRouter>);
  expect(step('Review').getByRole('link', { name: 'Open the review' })).toHaveAttribute('href', '#/r/TEST-owner/TEST-repo/901');
  expect(step('Build').getByText(/Built commit/)).not.toHaveTextContent('held, not pushed');
  rerender(<MemoryRouter><WorkItemSteps item={{ ...delivered, progress: { execution: { ...delivered.progress.execution, pullRequest: { ...pullRequest, draft: null } } } }} current={null} /></MemoryRouter>);
  expect(step('Deliver').getByRole('link', { name: 'Pull request · draft state unknown #901' })).toBeInTheDocument();
});
it('links the pinned specification and plan', () => {
  const artifact = (key: string) => ({ sha256: 'c'.repeat(64), location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: key }, issueKey: key, link: `https://TEST.example/${key}` } });
  show(item({ preparation: { specification: artifact('71'), plan: artifact('72'), baseBranch: 'main', baseCommit: 'd'.repeat(40), harness: 'TEST-harness', model: 'TEST-model', registeredBy: 'TEST-operator' } }));
  expect(step('Specification').getByRole('link', { name: 'Specification #71' })).toHaveAttribute('href', 'https://TEST.example/71');
  expect(step('Plan').getByRole('link', { name: 'Plan #72' })).toHaveAttribute('href', 'https://TEST.example/72');
});

// A composed text is a snapshot this deployment keeps, NOT a second ticket somebody wrote. Rendering
// it as "Specification #71" would send an operator to edit a ticket that no longer decides anything.
it('says a composed specification was composed, and still links where it came from', () => {
  const composed = (key: string) => ({ sha256: 'c'.repeat(64), origin: 'STORED' as const, storedId: 'TEST-stored-' + key,
    location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: key }, issueKey: key, link: `https://TEST.example/${key}` } });
  show(item({ preparation: { specification: composed('36'), plan: composed('36'), baseBranch: 'main', baseCommit: 'd'.repeat(40), harness: 'TEST-harness', model: 'TEST-model', registeredBy: 'system:build-defaults@1' } }));
  expect(step('Specification').getByText(/Specification composed from/)).toBeInTheDocument();
  expect(step('Specification').getByRole('link', { name: '#36' })).toHaveAttribute('href', 'https://TEST.example/36');
  expect(step('Plan').getByText(/Plan composed from/)).toBeInTheDocument();
});

// Review finding: decisions and runs from an earlier attempt read as proof for the current one.
it('keeps an earlier attempt out of the current proof', () => {
  const execution = { runId: 'TEST-old-run', build: { workItemId: 'TEST-item', generation: 1, buildAttemptId: 'TEST-old', preparationBinding: 'a'.repeat(64) },
    head: 'e'.repeat(40), verificationAttempt: null, pullRequest: null, reviewId: null };
  show(item({ generation: 2, phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: null, progress: { execution },
    builds: [{ attemptId: 'TEST-old', state: 'sent', runId: 'TEST-old-run', reason: null, generation: 1 }],
    events: [{ sequence: 3, type: 'GATE_RESOLVED', reason: 'approval_required', occurredAt: gate.openedAt, phase: 'plan', gateState: 'APPROVED', resolver: 'TEST-human', generation: 1 }] }));
  expect(step('Plan').queryByText(/plan decision: approved/i)).toBeNull();
  expect(step('Plan').getByText('1 decision from another attempt, in the history below')).toBeInTheDocument();
  expect(step('Build').getByText('Runs recorded: 0')).toBeInTheDocument();
  expect(step('Build').getByText('1 dispatch from an earlier attempt')).toBeInTheDocument();
  expect(step('Build').queryByText(/Built commit/)).toBeNull();
});

// Review finding: an entry that does not say its attempt was drawn as proof for the current one.
it('keeps a decision that does not name its attempt out of the current proof', () => {
  show(item({ phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: null,
    events: [{ sequence: 5, type: 'GATE_RESOLVED', reason: 'approval_required', occurredAt: gate.openedAt, phase: 'plan', gateState: 'APPROVED', resolver: 'TEST-human' }] }));
  expect(step('Plan').queryByText(/plan decision: approved/i)).toBeNull();
  expect(step('Plan').getByText('1 decision from another attempt, in the history below')).toBeInTheDocument();
});

const verification = (outcome: 'PASSED' | 'FAILED' | 'UNVERIFIED', reason: string | null, exitCode: number | null) => ({
  attemptId: 'TEST-verify', head: 'b'.repeat(40), outcome, reason,
  checks: exitCode === null ? [] : [{ command: 'TEST-check', exitCode, wallMillis: 1200, outputTail: 'TEST-tail' }] });
const executionWith = (value: ReturnType<typeof verification>): WorkExecution => ({ runId: 'TEST-run',
  build: { workItemId: 'TEST-item', generation: 1, buildAttemptId: 'TEST-attempt', preparationBinding: 'a'.repeat(64) },
  head: 'b'.repeat(40), verificationAttempt: 'TEST-verify', verification: value, pullRequest: null, reviewId: null });

it('an unverified result never shows a tick', () => {
  show(item({ phase: 'verify', workflowStatus: 'waiting_approval', reason: 'verify_unverified', gate: null,
    progress: { execution: executionWith(verification('UNVERIFIED', 'tool_missing', null)) } }));
  expect(step('Verify').getByText('Not checked')).toBeInTheDocument();
  expect(step('Verify').queryByLabelText('Passed')).toBeNull();
  expect(step('Verify').getByText(/A tool was probably missing/)).toBeInTheDocument();
});
it('a failed result names the command, its exit code, and opens its output', () => {
  show(item({ phase: 'verify', workflowStatus: 'waiting_approval', reason: 'verify_failed', gate: null,
    progress: { execution: executionWith(verification('FAILED', 'check_failed', 2)) } }));
  expect(step('Verify').getByLabelText('Failed')).toBeInTheDocument();
  expect(step('Verify').queryByText('TEST-tail')).toBeNull();
  fireEvent.click(step('Verify').getByRole('button', { name: /TEST-check/ }));
  expect(step('Verify').getByText('TEST-tail')).toBeInTheDocument();
});
it('a passed result shows the tick', () => {
  show(item({ phase: 'deliver', workflowStatus: 'active', reason: 'phase_started', gate: null,
    progress: { execution: executionWith(verification('PASSED', null, 0)) } }));
  expect(step('Verify').getByLabelText('Passed')).toBeInTheDocument();
});

// Review finding: a Jira ticket under the close rule stays open, and only the land note tells the operator to close it.
it('shows the land note beside a completed merge', () => {
  const pullRequest = { number: 901, url: 'https://forge.example.test/TEST-pull/901', draft: false };
  const execution: WorkExecution = { runId: 'TEST-run', build: { workItemId: 'TEST-item', generation: 1, buildAttemptId: 'TEST-attempt', preparationBinding: 'a'.repeat(64) },
    head: 'b'.repeat(40), verificationAttempt: null, pullRequest, reviewId: 'TEST-review',
    landing: { pullRequest, mergeCommit: 'c'.repeat(40), method: 'squash', mergedBy: 'factory', landedAt: '2026-10-22T12:00:00Z' } };
  show(item({ phase: 'complete', workflowStatus: 'completed', reason: 'landed', gate: null, progress: { execution },
    land: { state: 'done', reason: null, message: null, checks: null, method: 'squash', ticketRule: 'close', branchDeleted: true, note: 'ticket_close_unsupported',
      attempt: 0, attemptsAllowed: 3, fixInProgress: false, handedOver: false, stoppable: false } }));
  expect(step('Land').getByText('Merged ccccccc by the factory, branch deleted')).toBeInTheDocument();
  expect(step('Land').getByText(/Close it in Jira\.$/)).toBeInTheDocument();
});

// Live land acceptance (issue #62): two approvals of the same phase read the same, and the resolver was a raw subject.
it('shows each land decision with the head it approved and when, latest first, by name', () => {
  const resolved = (sequence: number, head: string, occurredAt: string) => ({ sequence, type: 'GATE_RESOLVED', reason: 'approval_required', occurredAt, phase: 'land',
    gateState: 'APPROVED', resolver: 'TEST-operator-subject', gateArtifact: head, gateChannel: 'dashboard', generation: 1 });
  show(item({ phase: 'land', workflowStatus: 'active', reason: 'phase_started', gate: null,
    operators: [{ subject: 'TEST-operator-subject', username: 'TEST-operator', displayName: 'TEST Operator' }],
    events: [resolved(10, '1'.repeat(40), '2026-10-23T10:00:00Z'), resolved(20, '2'.repeat(40), '2026-10-23T11:00:00Z')] }));
  const decisions = step('Land').getAllByText(/^land decision: approved by/);
  expect(decisions).toHaveLength(2);
  expect(decisions[0]).toHaveTextContent('2222222');
  expect(decisions[1]).toHaveTextContent('1111111');
  expect(decisions[0]).toHaveTextContent('TEST Operator');
  expect(decisions[0]).not.toHaveTextContent('TEST-operator-subject');
  expect(decisions[0].querySelector('time')).toHaveAttribute('dateTime', '2026-10-23T11:00:00Z');
});
it('keeps the resolver id when the operator was never seen on this dashboard', () => {
  show(item({ phase: 'land', workflowStatus: 'active', reason: 'phase_started', gate: null, operators: [],
    events: [{ sequence: 10, type: 'GATE_RESOLVED', reason: 'approval_required', occurredAt: '2026-10-23T10:00:00Z', phase: 'land',
      gateState: 'APPROVED', resolver: 'TEST-unknown-subject', gateArtifact: '1'.repeat(40), gateChannel: 'dashboard', generation: 1 }] }));
  expect(step('Land').getByText(/^land decision: approved by/)).toHaveTextContent('TEST-unknown-subject');
});
// Live land acceptance (issue #66): the note an operator gave Stop land was shown nowhere on the item.
it('shows the note of a land command on the Land step, with who and when', () => {
  show(item({ phase: 'land', workflowStatus: 'stopped', reason: 'land_stopped_by_operator', gate: null,
    operators: [{ subject: 'TEST-operator-subject', username: 'TEST-operator', displayName: 'TEST Operator' }],
    events: [{ sequence: 30, type: 'LAND_STOPPED', reason: 'land_stopped_by_operator', occurredAt: '2026-10-23T12:00:00Z', phase: 'land',
      workflowStatus: 'stopped', operator: 'TEST-operator-subject', note: 'TEST-merging it by hand', generation: 1 }] }));
  const command = step('Land').getByText(/^Land stopped by TEST Operator/);
  expect(command).toHaveTextContent('TEST-merging it by hand');
  expect(command.querySelector('time')).toHaveAttribute('dateTime', '2026-10-23T12:00:00Z');
});
it('shows the note of a land retry on the Land step', () => {
  show(item({ phase: 'land', workflowStatus: 'waiting_approval', reason: 'approval_required', gate: { ...gate, phase: 'land' }, operators: [],
    events: [{ sequence: 30, type: 'LAND_RETRY_REQUESTED', reason: 'approval_required', occurredAt: '2026-10-23T12:00:00Z', phase: 'land',
      workflowStatus: 'waiting_approval', operator: 'TEST-operator-subject', note: 'TEST-the code owners approved', generation: 1 }] }));
  expect(step('Land').getByText(/^Land retry requested by TEST-operator-subject/)).toHaveTextContent('TEST-the code owners approved');
});

// A plan the factory wrote builds step by step (M4 slice 3, §6.10): the Build step shows one row per plan step.
it('the build step lists plan steps', async () => {
  const rows: StepRow[] = [
    { index: 0, title: 'TEST-step-1', state: 'passed', head: '1'.repeat(40), summaryState: 'recorded', summary: { decisions: ['TEST-decision'], alternativesRejected: [], deviations: [] } },
    { index: 1, title: 'TEST-step-2', state: 'building', head: null, summaryState: null, summary: null },
  ];
  const read = vi.spyOn(preparationApi, 'workSteps').mockResolvedValue({ rows });
  const stored = (key: string) => ({ sha256: 'c'.repeat(64), origin: 'STORED' as const, storedId: 'TEST-stored-' + key,
    location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: key }, issueKey: key, link: `https://TEST.example/${key}` } });
  show(item({ phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: null, preparation: { specification: stored('81'), plan: stored('81'),
    baseBranch: 'main', baseCommit: 'd'.repeat(40), harness: 'TEST-harness', model: 'TEST-model', registeredBy: 'system:draft@1', bindingVersion: 6 } }));
  expect(await step('Build').findByText(/TEST-step-1/)).toBeInTheDocument();
  expect(step('Build').getByText(/TEST-step-2/)).toBeInTheDocument();
  expect(read).toHaveBeenCalledWith('TEST-item');
});
it('asks for no plan steps for a one-step preparation', () => {
  const read = vi.spyOn(preparationApi, 'workSteps');
  show(item({ phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: null }));
  expect(read).not.toHaveBeenCalled();
});

// A draft is read again whenever its generation writes another one (review of PR 287, M-2): a revised specification
// replaces the text first shown, and a plan written after the specification appears without a reload.
const written = (sequence: number): Item['events'][number] =>
  ({ sequence, type: 'DRAFT_ARTIFACT_WRITTEN', reason: 'specification_written', occurredAt: '2026-10-26T00:00:00Z', generation: 1 });
const specification = (text: string) => ({ text, sha256: 'a'.repeat(64), comment: null });
it('shows a revised specification once it is written', async () => {
  let text = '## Outcome\nTEST-first outcome';
  vi.spyOn(draftApi, 'workDraft').mockImplementation(async () => ({ specification: specification(text), plan: null,
    revisions: { specification: 1, plan: 0 }, ticketLink: 'https://TEST.example/81' }));
  const spec = item({ phase: 'spec', workflowStatus: 'waiting_approval', reason: 'specification_written', gate: { ...gate, phase: 'spec' } });
  const { rerender } = show({ ...spec, events: [written(3)] });
  await waitFor(() => expect(step('Specification').getByLabelText('Specification text')).toHaveTextContent('TEST-first outcome'));

  text = '## Outcome\nTEST-revised outcome';
  rerender(<MemoryRouter><WorkItemSteps item={{ ...spec, events: [written(3), written(7)] }} current={null} /></MemoryRouter>);

  await waitFor(() => expect(step('Specification').getByLabelText('Specification text')).toHaveTextContent('TEST-revised outcome'));
});
it('shows a plan written after the specification', async () => {
  let plan: WrittenPlan | null = null;
  vi.spyOn(draftApi, 'workDraft').mockImplementation(async () => ({ specification: specification('## Outcome\nTEST-outcome'), plan,
    revisions: { specification: 0, plan: 0 }, ticketLink: 'https://TEST.example/81' }));
  const running = item({ phase: 'plan', workflowStatus: 'active', reason: 'phase_started', gate: null });
  const { rerender } = show({ ...running, events: [written(3)] });
  await waitFor(() => expect(step('Specification').getByLabelText('Specification text')).toHaveTextContent('TEST-outcome'));
  expect(step('Plan').queryByRole('list', { name: 'Plan steps' })).toBeNull();

  plan = { text: '{"schemaVersion":2}', sha256: 'c'.repeat(64), comment: null,
    steps: [{ id: 'TEST-s1', title: 'TEST-written step', instruction: 'TEST-instruction', doneWhen: 'TEST-done' }] };
  rerender(<MemoryRouter><WorkItemSteps item={{ ...running, workflowStatus: 'waiting_approval', events: [written(3), written(8)] }} current={null} /></MemoryRouter>);

  expect(await step('Plan').findByRole('list', { name: 'Plan steps' })).toHaveTextContent('TEST-written step');
});
