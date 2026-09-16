import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import * as api from '../../api';
import WorkItems from './WorkItems';

function empty(title: string) {
  const heading = screen.getByText(title);
  expect(heading).toHaveClass('wh-empty-title');
  const panel = heading.closest('.wh-empty');
  expect(panel).not.toBeNull();
  expect(panel?.querySelector('.wh-empty-icon svg')).not.toBeNull();
  const guidance = panel?.querySelector('.wh-empty-text');
  expect(guidance).toBeInTheDocument();
  expect(guidance?.textContent ?? '').toMatch(/.{21}/);
}
it('welcomes the operator to an empty Work items screen', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [], total: 0, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  await screen.findByText('No work items yet.');
  empty('No work items yet.');
  expect(screen.getByText('Registered sources admit tickets after a scan or label event.')).toBeInTheDocument();
});
// Approvals are the "needs you" view of Work items; an empty view says so and how to widen it.
it('explains an empty filtered Work items view', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [], total: 0, offset: 0, limit: 50 });
  render(<MemoryRouter initialEntries={['/work-items?filter=needs-you']}><WorkItems /></MemoryRouter>);
  await screen.findByText('No work items match this filter.');
  empty('No work items match this filter.');
  expect(screen.getByText('Choose another filter to see more items.')).toBeInTheDocument();
});
