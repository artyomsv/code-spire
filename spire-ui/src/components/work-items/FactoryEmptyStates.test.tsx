import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import * as api from '../../api';
import * as auth from '../../auth';
import * as sources from './workSourcesApi';
import * as repositories from '../repositories/repositoriesApi';
import * as approvals from './approvalsApi';
import WorkSources from './WorkSources';
import WorkItems from './WorkItems';
import Approvals from './Approvals';

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
it('welcomes the operator to an empty Work sources screen', async () => {
  vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
  vi.spyOn(repositories, 'fetchRepositories').mockResolvedValue([]);
  vi.spyOn(sources, 'fetchWorkSources').mockResolvedValue([]);
  render(<WorkSources />);
  await screen.findByText('No work sources registered.');
  empty('No work sources registered.');
  expect(screen.getByText(/Choose Add work source/)).toBeInTheDocument();
});
it('welcomes the operator to an empty Work items screen', async () => {
  vi.spyOn(api, 'getWorkItems').mockResolvedValue({ items: [], total: 0, offset: 0, limit: 50 });
  render(<MemoryRouter><WorkItems /></MemoryRouter>);
  await screen.findByText('No work items yet.');
  empty('No work items yet.');
  expect(screen.getByText('Registered sources admit tickets after a scan or label event.')).toBeInTheDocument();
});
it('welcomes the operator to an empty Approvals screen', async () => {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: false, authenticated: true, user: 'TEST-operator', roles: [] });
  vi.spyOn(approvals, 'approvals').mockResolvedValue([]);
  render(<MemoryRouter><Approvals /></MemoryRouter>);
  await screen.findByText('No open approvals.');
  empty('No open approvals.');
  expect(screen.getByText(/When a work item needs your approval/)).toBeInTheDocument();
});
