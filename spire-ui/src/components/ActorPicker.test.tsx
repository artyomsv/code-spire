import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';

import { beforeEach, expect, test, vi } from 'vitest';
import ActorPicker from './ActorPicker';
import * as api from './actorsApi';
import { queryWrapper, renderWithQuery } from '../test/queryWrapper';
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

  const first = renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" />);
  await user.type(screen.getByLabelText('Person'), '@TEST-person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  expect(api.resolveActor).toHaveBeenCalledWith('/api/providers/TEST-account/actors', { handle: '@TEST-person' });
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 2, actors: [stored] });
  await user.click(screen.getByRole('button', { name: 'Save person' }));
  expect(api.saveActor).toHaveBeenCalledWith('/api/providers/TEST-account/actors', {
    handle: '@TEST-person', providerUserId: '900123', revision: 1, effect: 'ALLOW',
  });
  first.unmount();
  renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" />);
  const list = await screen.findByRole('list');
  expect(within(list).getByText('@TEST-person')).toBeVisible();
  expect(within(list).queryByText('900123')).not.toBeInTheDocument();
  expect(within(list).queryByText('1')).not.toBeInTheDocument();
  expect(list).toHaveTextContent('Allowed');
});

test('shows the people at once when the editor opens again, and saves only after this visit\'s read', async () => {
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 2, actors: [stored] });
  const wrapper = queryWrapper();
  const first = renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" />, { wrapper });
  expect(within(await screen.findByRole('list')).getByText('@TEST-person')).toBeVisible();
  first.unmount();
  vi.mocked(api.fetchActors).mockReturnValue(new Promise(() => {}));
  renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" />, { wrapper });
  expect(within(screen.getByRole('list')).getByText('@TEST-person')).toBeVisible();
  await user.type(screen.getByLabelText('Person'), '@TEST-person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  // A cached policy's revision may be out of date: neither Save nor Remove may send it.
  expect(screen.getByRole('button', { name: 'Save person' })).toBeDisabled();
  expect(screen.getByRole('button', { name: /^Remove/ })).toBeDisabled();
});

test('shows the refreshed names after Reload, with no plain read replacing them', async () => {
  renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" />);
  await screen.findByRole('list');
  vi.mocked(api.fetchActors).mockClear();
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 2, actors: [{ ...stored, handle: 'TEST-renamed' }] });
  await user.click(screen.getByRole('button', { name: 'Reload people and refresh names' }));
  expect(await screen.findByText('@TEST-renamed')).toBeVisible();
  expect(vi.mocked(api.fetchActors).mock.calls).toEqual([['/api/providers/TEST-account/actors', true]]);
});

test('refuses unresolved input', async () => {
  vi.mocked(api.resolveActor).mockRejectedValue(new Error('No exact handle was found.'));

  renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" />);
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

  renderWithQuery(<ActorPicker accountId="TEST-jira" accountType="atlassian" />);
  expect(screen.getByText(/Browse users and groups/)).toBeVisible();
  await user.type(screen.getByLabelText('Person'), 'TEST Person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  expect(screen.getByRole('button', { name: 'Save person' })).toBeDisabled();
  expect(screen.getByRole('option', { name: /TEST Person.*TEST-jira-other/ })).toBeVisible();
  await user.selectOptions(screen.getByLabelText('Resolved person'), 'TEST-jira-other');
  expect(screen.getByRole('button', { name: 'Save person' })).toBeEnabled();
});

test('changing the input discards the resolved selection', async () => {

  renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" />);
  await user.type(screen.getByLabelText('Person'), '@TEST-person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  expect(screen.getByRole('button', { name: 'Save person' })).toBeEnabled();
  await user.type(screen.getByLabelText('Person'), '-changed');
  expect(screen.getByRole('button', { name: 'Save person' })).toBeDisabled();
});

test('edits a repository deny with the stored version', async () => {
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 0, actors: [stored] });

  renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" repositoryId="TEST-repo" />);
  await user.type(screen.getByLabelText('Person'), '@TEST-person');
  await user.click(screen.getByRole('button', { name: 'Resolve person' }));
  await user.selectOptions(screen.getByLabelText('Policy effect'), 'DENY');
  await user.click(screen.getByRole('button', { name: 'Save person' }));
  expect(api.saveActor).toHaveBeenCalledWith('/api/repositories/TEST-repo/fix-actors', {
    handle: '@TEST-person', providerUserId: '900123', effect: 'DENY', revision: 2,
  });
});

// Removing a person changes who may command the bot, so the row's Remove only asks.
async function askToRemove(repositoryId?: string) {
  vi.mocked(api.fetchActors).mockResolvedValue({ revision: 1, actors: [stored] });
  vi.mocked(api.deleteActor).mockResolvedValue(undefined as never);
  renderWithQuery(<ActorPicker accountId="TEST-account" accountType="github" repositoryId={repositoryId} />);
  const row = await screen.findByRole('button', { name: 'Remove @TEST-person' });
  await waitFor(() => expect(row).toBeEnabled());
  await user.click(row);
  return screen.getByRole('dialog');
}

test('asks before removing an allowed person, naming what it means', async () => {
  const dialog = await askToRemove();
  expect(dialog).toHaveTextContent('Remove @TEST-person from the allowed people? Their review commands and conversations are no longer accepted until they are added again.');
  expect(api.deleteActor).not.toHaveBeenCalled();
});

test('asks before removing a fix override, naming what it means', async () => {
  const dialog = await askToRemove('TEST-repo');
  expect(dialog).toHaveTextContent('Remove the override for @TEST-person? Whether they may use /fix on this repository is decided by their push access again.');
  expect(api.deleteActor).not.toHaveBeenCalled();
});

test('removes the person once the dialog is confirmed', async () => {
  const dialog = await askToRemove();
  await user.click(within(dialog).getByRole('button', { name: 'Remove' }));
  expect(api.deleteActor).toHaveBeenCalledWith('/api/providers/TEST-account/actors', stored, 1);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

test('keeps the person when the dialog is cancelled', async () => {
  const dialog = await askToRemove();
  await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(api.deleteActor).not.toHaveBeenCalled();
});
