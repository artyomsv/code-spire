import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import WorkItemPolicy from './WorkItemPolicy';

afterEach(cleanup);
it('shows requested and effective profiles with the clamp reason', () => {
  render(<WorkItemPolicy item={{ profile: { id: 'TEST-high', name: 'TEST-autonomous', version: 2 },
    ceiling: { id: 'TEST-low', name: 'TEST-assisted', version: 3 }, policyReason: 'policy_clamped',
    effectiveModes: { PLAN: 'approve', DELIVER: 'draft_pr' }, admittedModes: { PLAN: 'auto', DELIVER: 'pr' } }} />);
  const policy = within(screen.getByRole('region', { name: 'Work item policy' }));
  expect(policy.getByText('Selected profile: TEST-autonomous v2')).toBeInTheDocument();
  expect(policy.getByText('Repository ceiling: TEST-assisted v3')).toBeInTheDocument();
  expect(policy.getByText('The effective policy is restricted by another label, the admission version, or the ceiling.')).toBeInTheDocument();
  expect(policy.getByRole('row', { name: 'plan approve auto' })).toBeInTheDocument();
  expect(policy.getByRole('row', { name: 'deliver draft_pr pr' })).toBeInTheDocument();
});
