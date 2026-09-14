import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, it } from 'vitest';
import type { WorkItemDetail } from '../../api';
import type { Gate } from './approvalsApi';
import WorkItemJourney from './WorkItemJourney';
import type { WorkExecution } from './workPreparationApi';

afterEach(cleanup);
const gate: Gate = { id: 'TEST-plan-gate', version: 1, state: 'OPEN', phase: 'plan', generation: 1, itemRevision: 4, policyRevision: 1,
  artifact: 'TEST-digest', openedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-09-14T01:00:00Z', resolver: null, channel: null, note: null };
type Journey = Pick<WorkItemDetail, 'phase' | 'workflowStatus' | 'reason' | 'preparation' | 'builds' | 'gate' | 'events'>;
it('renders distinct suggest assisted and autonomous journeys', () => {
  // No profile names reach this component. The visible distinction must come from actual workflow facts.
  const suggest: Journey = { phase: 'build', workflowStatus: 'not_eligible', reason: 'build_off', builds: [], gate: null, events: [] };
  const assisted: Journey = { phase: 'plan', workflowStatus: 'waiting_approval', reason: 'approval_required', builds: [], gate, events: [] };
  const autonomous: Journey = { phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: null, events: [],
    builds: [{ attemptId: 'TEST-attempt', state: 'sent', runId: 'TEST-autonomous-run', reason: null, generation: 1 }] };
  const { rerender } = render(<MemoryRouter><WorkItemJourney item={suggest} /><WorkItemJourney item={assisted} /><WorkItemJourney item={autonomous} /></MemoryRouter>);
  const [stopped, waiting, building] = screen.getAllByRole('region', { name: 'Work item journey' }).map(region => within(region));
  expect(stopped.getByText('Not eligible')).toBeInTheDocument();expect(stopped.getByText('The current policy stops before building.')).toBeInTheDocument();
  expect(stopped.getByText('Runs recorded: 0')).toBeInTheDocument();expect(stopped.queryByRole('link')).toBeNull();
  expect(waiting.getByText('Waiting for approval')).toBeInTheDocument();expect(waiting.getByText('Phase: plan')).toBeInTheDocument();
  expect(waiting.getByText('Runs recorded: 0')).toBeInTheDocument();expect(waiting.getByRole('link', { name: 'Review this decision' })).toHaveAttribute('href', '/approvals');
  expect(building.getByText('Active')).toBeInTheDocument();expect(building.getByText('Phase: build')).toBeInTheDocument();
  expect(building.getByText('Runs recorded: 1')).toBeInTheDocument();expect(building.getByRole('link', { name: 'Open run' })).toHaveAttribute('href', '/runs/TEST-autonomous-run');
  expect(building.queryByRole('link', { name: 'Review this decision' })).toBeNull();
  rerender(<MemoryRouter><WorkItemJourney item={{ ...assisted, phase: 'build', workflowStatus: 'active', reason: 'phase_started', gate: { ...gate, state: 'APPROVED', resolver: 'TEST-human' },
    builds: [{ attemptId: 'TEST-assisted-attempt', state: 'sent', runId: 'TEST-assisted-run', reason: null, generation: 1 }],
    events: [{ sequence: 5, type: 'GATE_RESOLVED', reason: 'approval_required', occurredAt: gate.openedAt, phase: 'plan', gateState: 'APPROVED', resolver: 'TEST-human' }] }} /></MemoryRouter>);
  expect(screen.getByText('plan decision: APPROVED by TEST-human')).toBeInTheDocument();
  expect(screen.getByText('Runs recorded: 1')).toBeInTheDocument();expect(screen.queryByRole('link', { name: 'Review this decision' })).toBeNull();
});

it('shows build evidence and the observed delivery state', () => {
  const execution: WorkExecution = { runId: 'TEST-run', build: { workItemId: 'TEST-item', generation: 1,
    buildAttemptId: 'TEST-attempt', preparationBinding: 'a'.repeat(64) }, head: 'b'.repeat(40),
    verificationAttempt: null, pullRequest: null, reviewId: null };
  const item = { phase: 'verify', workflowStatus: 'capability_unavailable', reason: 'verify_capability_unavailable',
    builds: [], gate: null, events: [], progress: { execution } };
  const { rerender } = render(<MemoryRouter><WorkItemJourney item={item} /></MemoryRouter>);
  expect(screen.getByRole('region', { name: 'Work item journey' })).toHaveTextContent(execution.head);
  expect(screen.getByText('Verification not recorded')).toBeInTheDocument();
  expect(screen.queryByRole('link')).toBeNull();expect(screen.queryByText('Review recorded for this build.')).toBeNull();
  const verified = { ...execution, verificationAttempt: 'TEST-verification' };
  const pullRequest = { number: 901, url: 'https://forge.example.test/TEST-pull/901', draft: true };
  rerender(<MemoryRouter><WorkItemJourney item={{ ...item, progress: { execution: { ...verified, pullRequest, reviewId: 'TEST-review' } } }} /></MemoryRouter>);
  expect(screen.getByText('Verification recorded')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Draft pull request #901' })).toHaveAttribute('href', pullRequest.url);
  expect(screen.getByText('Review recorded for this build.')).toBeInTheDocument();
  rerender(<MemoryRouter><WorkItemJourney item={{ ...item, progress: { execution: { ...verified, pullRequest: { ...pullRequest, draft: false } } } }} /></MemoryRouter>);
  expect(screen.getByRole('link', { name: 'Pull request #901' })).toBeInTheDocument();
  rerender(<MemoryRouter><WorkItemJourney item={{ ...item, progress: { execution: { ...verified, pullRequest: { ...pullRequest, draft: null } } } }} /></MemoryRouter>);
  expect(screen.getByRole('link', { name: 'Pull request · draft state unknown #901' })).toBeInTheDocument();
});
