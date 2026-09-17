import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it } from 'vitest';
import type { Gate } from './approvalsApi';
import WorkItemSteps from './WorkItemSteps';
import type { WorkExecution } from './workPreparationApi';

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
  expect(step('Plan').getByText('plan decision: APPROVED by TEST-human')).toBeInTheDocument();
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
  expect(step('Verify').getByText('Verification recorded')).toBeInTheDocument();
  expect(step('Deliver').getByRole('link', { name: 'Draft pull request #901' })).toHaveAttribute('href', pullRequest.url);
  expect(step('Review').getByText('Review recorded for this build.')).toBeInTheDocument();
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
  expect(step('Plan').queryByText(/plan decision: APPROVED/)).toBeNull();
  expect(step('Plan').getByText('1 decision from another attempt, in the history below')).toBeInTheDocument();
  expect(step('Build').getByText('Runs recorded: 0')).toBeInTheDocument();
  expect(step('Build').getByText('1 dispatch from an earlier attempt')).toBeInTheDocument();
  expect(step('Build').queryByText(/Built commit/)).toBeNull();
});

// Review finding: an entry that does not say its attempt was drawn as proof for the current one.
it('keeps a decision that does not name its attempt out of the current proof', () => {
  show(item({ phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: null,
    events: [{ sequence: 5, type: 'GATE_RESOLVED', reason: 'approval_required', occurredAt: gate.openedAt, phase: 'plan', gateState: 'APPROVED', resolver: 'TEST-human' }] }));
  expect(step('Plan').queryByText(/plan decision: APPROVED/)).toBeNull();
  expect(step('Plan').getByText('1 decision from another attempt, in the history below')).toBeInTheDocument();
});
