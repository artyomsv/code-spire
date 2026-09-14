import { act, fireEvent, render, screen, within } from '@testing-library/react';

import { beforeEach, expect, test, vi } from 'vitest';
import ActorPicker from './ActorPicker';
import * as api from './actorsApi';
vi.mock('./actorsApi', async importOriginal => ({ ...await importOriginal<typeof api>(),
  fetchActors: vi.fn(), resolveActor: vi.fn(), saveActor: vi.fn(), deleteActor: vi.fn() }));

const user = {
  type: async (element: HTMLElement, value: string) => { await act(async () => { fireEvent.change(element, { target: { value: (element as HTMLInputElement).value + value } }); }); },
  click: async (element: HTMLElement) => { await act(async () => { fireEvent.click(element); }); },
  selectOptions: async (element: HTMLElement, value: string) => { await act(async () => { fireEvent.change(element, { target: { value } }); }); },
};
const actor = { providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST Person' };
const stored = { ...actor, resolvedAt: '2026-09-13T00:00:00Z', stale: false, effect: 'ALLOW' as const, revision: 2 };
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 1, actors: [] });
  vi.mocked(api.resolveActor).mockResolvedValue({ status: 'FOUND', actors: [actor], detail: null });
  vi.mocked(api.saveActor).mockResolvedValue({ revision: 2, actors: [stored] });
});

test('saves a resolved handle and renders it after reload', async () => {

  const first = render(<ActorPicker accountId="TEST-account" accountType="github" />);
  await user.type(screen.getByLabelText('Person'), '@TEST-person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  expect(api.resolveActor).toHaveBeenCalledWith('/api/providers/TEST-account/actors', { handle: '@TEST-person' });
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 2, actors: [stored] });
  await user.click(screen.getByRole('button', { name: 'Save person' }));
  expect(api.saveActor).toHaveBeenCalledWith('/api/providers/TEST-account/actors', {
    handle: '@TEST-person', providerUserId: '900123', revision: 1, effect: 'ALLOW',
  });
  first.unmount();
  render(<ActorPicker accountId="TEST-account" accountType="github" />);
  const list = await screen.findByRole('list');
  expect(within(list).getByText('@TEST-person')).toBeVisible();
  expect(within(list).queryByText('900123')).not.toBeInTheDocument();
  expect(within(list).queryByText('1')).not.toBeInTheDocument();
  expect(list).toHaveTextContent('Allowed');
});

test('refuses unresolved input', async () => {
  vi.mocked(api.resolveActor).mockRejectedValue(new Error('No exact handle was found.'));

  render(<ActorPicker accountId="TEST-account" accountType="github" />);
  await user.type(screen.getByLabelText('Person'), '@TEST-missing');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('No exact handle was found');
  expect(screen.getByRole('button', { name: 'Save person' })).toBeDisabled();
  expect(api.saveActor).not.toHaveBeenCalled();
});

test('requires explicit selection for nonunique Jira display names', async () => {
  vi.mocked(api.resolveActor).mockResolvedValue({ status: 'SELECTION_REQUIRED', actors: [
    { ...actor, handle: '', providerUserId: 'TEST-jira-person' },
    { ...actor, handle: '', providerUserId: 'TEST-jira-other' },
  ], detail: 'Select a person.' });

  render(<ActorPicker accountId="TEST-jira" accountType="atlassian" />);
  expect(screen.getByText(/Browse users and groups/)).toBeVisible();
  await user.type(screen.getByLabelText('Person'), 'TEST Person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  expect(screen.getByRole('button', { name: 'Save person' })).toBeDisabled();
  expect(screen.getByRole('option', { name: /TEST Person.*TEST-jira-other/ })).toBeVisible();
  await user.selectOptions(screen.getByLabelText('Resolved person'), 'TEST-jira-other');
  expect(screen.getByRole('button', { name: 'Save person' })).toBeEnabled();
});

test('changing the input discards the resolved selection', async () => {

  render(<ActorPicker accountId="TEST-account" accountType="github" />);
  await user.type(screen.getByLabelText('Person'), '@TEST-person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  expect(screen.getByRole('button', { name: 'Save person' })).toBeEnabled();
  await user.type(screen.getByLabelText('Person'), '-changed');
  expect(screen.getByRole('button', { name: 'Save person' })).toBeDisabled();
});

test('edits a repository deny with the stored version', async () => {
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 0, actors: [stored] });

  render(<ActorPicker accountId="TEST-account" accountType="github" repositoryId="TEST-repo" />);
  await user.type(screen.getByLabelText('Person'), '@TEST-person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  await user.selectOptions(screen.getByLabelText('Policy effect'), 'DENY');
  await user.click(screen.getByRole('button', { name: 'Save person' }));
  expect(api.saveActor).toHaveBeenCalledWith('/api/repositories/TEST-repo/fix-actors', {
    handle: '@TEST-person', providerUserId: '900123', effect: 'DENY', revision: 2,
  });
});
