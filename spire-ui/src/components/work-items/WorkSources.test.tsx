import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import * as accounts from '../../api';
import * as repositories from '../repositories/repositoriesApi';
import * as api from './workSourcesApi';
import WorkSources from './WorkSources';

function account(type = 'github', enabled = true): accounts.ProviderView {
  return { id: `TEST-${type}`, name: `TEST-${type} account`, type, baseUrl: `https://${type}.example.test`, authKind: type === 'atlassian' ? 'basic' : 'bearer',
    authUsername: null, hasSecret: true, botAccountId: '900001', enabled, authors: [], conversationLevel: null,
    role: type === 'atlassian' ? 'CONTEXT' : 'FACTORY', botUsername: 'TEST-bot', createdAt: '2026-09-13T12:00:00Z', lastCheckAt: null, lastCheckOk: null, lastCheckError: null };
}
const repository: repositories.Repository = { id: 'TEST-repository', scmType: 'github', forgeOrigin: 'https://github.example.test', workspace: 'TEST-owner', slug: 'TEST-repo', enabled: true, revision: 1, reviewer: null, factory: null };
function source(type: api.WorkSourceType = 'GITHUB'): api.WorkSource {
  return { id: 'TEST-source', name: 'TEST-source name', type, origin: type === 'JIRA' ? 'https://atlassian.example.test' : 'https://github.example.test', projectId: '10001', scope: 'TEST-owner/TEST-repo',
    repositoryId: repository.id, accountId: type === 'JIRA' ? 'TEST-atlassian' : 'TEST-github', enabled: true, configuredEnabled: true, version: { source: 4, repository: 1, account: 1 }, cursor: null, health: 'healthy', allowedActors: ['900123'], repository: { workspace: 'TEST-owner', slug: 'TEST-repo' } };
}
beforeEach(() => {
  openInfo = null;
  vi.spyOn(accounts, 'fetchProviders').mockResolvedValue([account(), account('gitlab'), account('atlassian')]);
  vi.spyOn(repositories, 'fetchRepositories').mockResolvedValue([repository]);
  vi.spyOn(api, 'fetchWorkSources').mockResolvedValue([source()]);
  vi.spyOn(api, 'createWorkSource').mockResolvedValue(source());
  vi.spyOn(api, 'editWorkSource').mockResolvedValue(source());
  vi.spyOn(api, 'rescanWorkSource').mockResolvedValue();
  vi.spyOn(api, 'saveWorkActor').mockResolvedValue(source());
});
async function addSource() {
  const button = await screen.findByRole('button', { name: 'Add work source' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}
async function selectSource() { fireEvent.click(await screen.findByRole('link', { name: 'TEST-source name' })); }

/**
 * A SettingField carries its explanation on an info control; focusing it reveals the tooltip.
 * Blur the previous trigger first — two open bubbles would make the role query ambiguous.
 */
let openInfo: HTMLElement | null = null;
function hintOf(label: string) {
  if (openInfo) fireEvent.blur(openInfo);
  // Anchored on the separator: a bare prefix would match "tracker" and "tracker account" alike.
  const info = screen.getByRole('button', { name: `About ${label.toLowerCase()} — work source` });
  fireEvent.focus(info);
  openInfo = info;
  return screen.getByRole('tooltip');
}

it('opens on the source list and reveals creation only through Add', async () => {
  render(<WorkSources />);
  await screen.findByRole('link', { name: 'TEST-source name' });
  expect(screen.queryByLabelText('Source name', { selector: 'input,select,textarea' })).toBeNull();
  await addSource(); expect(screen.getByLabelText('Source name', { selector: 'input,select,textarea' })).toBeRequired();
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByLabelText('Source name', { selector: 'input,select,textarea' })).toBeNull();
});

it('explains the missing account before offering otherwise compatible repositories', async () => {
  render(<WorkSources />); await addSource();
  const select = screen.getByLabelText('Target repository', { selector: 'input,select,textarea' });
  expect(select).toBeDisabled();
  expect(within(select).getByRole('option')).toHaveTextContent('Choose a tracker account first');
  expect(hintOf('Target repository')).toHaveTextContent('Choose a tracker account first');
  fireEvent.change(screen.getByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  expect(select).toBeEnabled();
  expect(within(select).getByRole('option', { name: 'TEST-owner/TEST-repo' })).toBeInTheDocument();
});

it('explains an origin mismatch after account selection and links repository registration', async () => {
  // Enabled and the same SCM kind: only the origin excludes this repository.
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([{ ...repository, forgeOrigin: 'https://TEST-other.invalid' }]);
  render(<WorkSources />); await addSource();
  fireEvent.change(screen.getByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  const select = screen.getByLabelText('Target repository', { selector: 'input,select,textarea' });
  expect(select).toBeDisabled();
  expect(within(select).getByRole('option')).toHaveTextContent('No registered repository matches https://github.example.test');
  expect(hintOf('Target repository')).toHaveTextContent('No registered repository matches https://github.example.test');
  expect(screen.getByRole('link', { name: 'Register a repository' })).toHaveAttribute('href', '#/settings/repositories');
});

it('distinguishes duplicate credential names in both source account pickers', async () => {
  vi.mocked(accounts.fetchProviders).mockResolvedValue([
    { ...account(), name: 'TEST-shared name' },
    { ...account(), id: 'TEST-reviewer', name: 'TEST-shared name', role: 'REVIEWER' },
  ]);
  render(<WorkSources />); await addSource(); await selectSource();
  for (const label of ['Tracker account', 'Source account']) {
    const select = screen.getByLabelText(label, { selector: 'input,select,textarea' });
    expect(within(select).getByRole('option', { name: 'TEST-shared name · github · Factory' })).toHaveValue('TEST-github');
    expect(within(select).getByRole('option', { name: 'TEST-shared name · github · Reviewer' })).toHaveValue('TEST-reviewer');
  }
});

it('labels the automatic tracker scope and explains how to change it', async () => {
  render(<WorkSources />); await addSource();
  const scope = screen.getByLabelText('Tracker repository', { selector: 'input,select,textarea' });
  expect(scope).toHaveAttribute('readonly');
  expect(hintOf('Tracker repository')).toHaveTextContent('Filled automatically from the target repository');
  for (const label of ['Source name', 'Tracker', 'Tracker account', 'Target repository']) {
    const control = screen.getByLabelText(label, { selector: 'input,select,textarea' });
    expect(control).toBeRequired();
    expect(hintOf(label)).not.toHaveTextContent(/^$/);
  }
  fireEvent.change(screen.getByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  fireEvent.change(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' }), { target: { value: repository.id } });
  expect(scope).toHaveValue('TEST-owner/TEST-repo');
});

it('shows the returned source in the list and confirms registration', async () => {
  vi.mocked(api.createWorkSource).mockResolvedValue({ ...source(), id: 'TEST-created', name: 'TEST-visible source' });
  render(<WorkSources />); await addSource();
  fireEvent.change(screen.getByLabelText('Source name', { selector: 'input,select,textarea' }), { target: { value: 'TEST-visible source' } });
  fireEvent.change(screen.getByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  fireEvent.change(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' }), { target: { value: repository.id } });
  fireEvent.click(screen.getByRole('button', { name: 'Register work source' }));
  expect(await screen.findByRole('link', { name: 'TEST-visible source' })).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('Work source TEST-visible source registered.');
  expect(screen.queryByLabelText('Source name', { selector: 'input,select,textarea' })).toBeNull();
});

it('styles both forms and keeps all their controls locked during a save', async () => {
  let finishCreate!: (value: api.WorkSource) => void, finishEdit!: (value: api.WorkSource) => void;
  vi.mocked(api.createWorkSource).mockReturnValue(new Promise(done => { finishCreate = done; }));
  vi.mocked(api.editWorkSource).mockReturnValue(new Promise(done => { finishEdit = done; }));
  render(<WorkSources />);await addSource();
  const name = await screen.findByRole('link', { name: 'TEST-source name' });
  expect(name).toHaveClass('mono', 'nowrap');
  fireEvent.click(name);
  for (const label of ['Source name', 'Tracker', 'Tracker account', 'Target repository', 'Tracker repository', 'Edit source name', 'Source account', 'Person']) {
    expect(screen.getByLabelText(label, { selector: 'input,select,textarea' }).closest('label')).toHaveClass('field');
  }
  const create = screen.getByRole('group', { name: 'Add a work source' });
  const edit = screen.getByRole('group', { name: 'TEST-source name' });
  // The fieldset stays — it is what disables every control while a save is in flight — but its
  // native chrome is now stripped by `.form-lock` instead of an inline style.
  expect(create).toHaveClass('form-lock'); expect(edit).toHaveClass('form-lock');
  fireEvent.change(screen.getByLabelText('Source name', { selector: 'input,select,textarea' }), { target: { value: 'TEST-create' } });
  fireEvent.change(screen.getByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  fireEvent.change(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' }), { target: { value: repository.id } });
  fireEvent.click(screen.getByRole('button', { name: 'Register work source' }));
  for (const element of create.querySelectorAll('input,select,button')) expect(element).toBeDisabled();
  expect(api.createWorkSource).toHaveBeenCalledTimes(1);
  await act(async () => finishCreate(source()));
  const savedEdit = screen.getByRole('group', { name: 'TEST-source name' });
  fireEvent.click(screen.getByRole('button', { name: 'Save source' }));
  for (const element of savedEdit.querySelectorAll('input,select,button')) expect(element).toBeDisabled();
  expect(api.editWorkSource).toHaveBeenCalledTimes(1);
  await act(async () => finishEdit(source()));
});

it('registers a source with an explicit compatible account and repository', async () => {
  render(<WorkSources />);await addSource();
  fireEvent.change(await screen.findByLabelText('Source name', { selector: 'input,select,textarea' }), { target: { value: 'TEST-new source' } });
  fireEvent.change(screen.getByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  fireEvent.change(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' }), { target: { value: repository.id } });
  fireEvent.click(screen.getByRole('button', { name: 'Register work source' }));
  await waitFor(() => expect(api.createWorkSource).toHaveBeenCalledWith({ name: 'TEST-new source', type: 'GITHUB', origin: 'https://github.example.test', scope: 'TEST-owner/TEST-repo', repositoryId: repository.id, accountId: 'TEST-github', enabled: true }));
});
it('maps a Jira project to a repository on a different forge', async () => {
  render(<WorkSources />);await addSource();
  fireEvent.change(await screen.findByLabelText('Tracker', { selector: 'input,select,textarea' }), { target: { value: 'JIRA' } });
  fireEvent.change(screen.getByLabelText('Source name', { selector: 'input,select,textarea' }), { target: { value: 'TEST-Jira source' } });
  fireEvent.change(screen.getByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-atlassian' } });
  fireEvent.change(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' }), { target: { value: repository.id } });
  fireEvent.change(screen.getByLabelText('Jira project key', { selector: 'input,select,textarea' }), { target: { value: 'TEST' } });
  fireEvent.click(screen.getByRole('button', { name: 'Register work source' }));
  await waitFor(() => expect(api.createWorkSource).toHaveBeenCalledWith(expect.objectContaining({ type: 'JIRA', origin: 'https://atlassian.example.test', scope: 'TEST', repositoryId: repository.id })));
});
it('does not offer disabled or incompatible accounts', async () => {
  vi.mocked(accounts.fetchProviders).mockResolvedValue([account('github', false), account('gitlab'), account('atlassian')]);
  render(<WorkSources />);await addSource();const select = await screen.findByLabelText('Tracker account', { selector: 'input,select,textarea' });
  expect(within(select).getAllByRole('option')).toHaveLength(1);
  expect(screen.getByRole('button', { name: 'Register work source' })).toBeDisabled();
});
it('preserves the configured enabled flag when the account is disabled', async () => {
  vi.mocked(api.fetchWorkSources).mockResolvedValue([{ ...source(), enabled: false, configuredEnabled: true }]);
  vi.mocked(accounts.fetchProviders).mockResolvedValue([account('github', false)]);
  render(<WorkSources />);await addSource();await selectSource();
  expect(screen.getByLabelText('Source enabled', { selector: 'input,select,textarea' })).toBeChecked();
  expect(screen.getByText(/This source is enabled, but its account or repository is unavailable/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Request rescan' })).toBeDisabled();
});
it('requires explicit Jira account selection and saves the source revision', async () => {
  const initial = source('JIRA');vi.mocked(api.fetchWorkSources).mockResolvedValue([initial]);
  vi.spyOn(api, 'resolveWorkActor').mockResolvedValue({ status: 'SELECTION_REQUIRED', actors: [{ providerUserId: '900123', handle: '', displayName: 'TEST-person' }], detail: 'Select an account explicitly.' });
  render(<WorkSources />);await addSource();await selectSource();
  fireEvent.change(screen.getByLabelText('Person', { selector: 'input,select,textarea' }), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));
  const select = await screen.findByLabelText('Resolved source person', { selector: 'input,select,textarea' });
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
  fireEvent.change(select, { target: { value: '900123' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save source person' }));
  await waitFor(() => expect(api.saveWorkActor).toHaveBeenCalledWith(initial, 'TEST-person', '900123'));
});
it('discards a resolved selection when the typed person changes', async () => {
  vi.spyOn(api, 'resolveWorkActor').mockResolvedValue({ status: 'FOUND', actors: [{ providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST-person' }], detail: null });
  render(<WorkSources />);await addSource();await selectSource();
  fireEvent.change(screen.getByLabelText('Person', { selector: 'input,select,textarea' }), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));
  await screen.findByLabelText('Resolved source person', { selector: 'input,select,textarea' });
  fireEvent.change(screen.getByLabelText('Person', { selector: 'input,select,textarea' }), { target: { value: 'TEST-someone-else' } });
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
});
it('requests a rescan and displays the reported capability limits', async () => {
  vi.spyOn(api, 'workCapabilities').mockResolvedValue({ operations: ['CANDIDATES', 'COMMENT'], detail: 'TEST-this deployment cannot attribute labels.' });
  render(<WorkSources />);await addSource();await selectSource();fireEvent.click(screen.getByRole('button', { name: 'Request rescan' }));
  await waitFor(() => expect(api.rescanWorkSource).toHaveBeenCalledWith('TEST-source'));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Check supported operations' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'Check supported operations' }));
  expect(await screen.findByText(/TEST-this deployment cannot attribute labels/)).toHaveTextContent('candidates, comment');
});
it('ignores a superseded list response', async () => {
  let resolve!: (value: api.WorkSource[]) => void;
  vi.mocked(api.fetchWorkSources).mockReturnValueOnce(new Promise(yes => { resolve = yes; })).mockResolvedValue([{ ...source(), name: 'TEST-current source' }]);
  render(<WorkSources />);fireEvent.click(screen.getByRole('button', { name: 'Refresh work sources' }));
  await screen.findByRole('link', { name: 'TEST-current source' });
  await act(async () => resolve([source()]));
  expect(screen.queryByRole('link', { name: 'TEST-source name' })).toBeNull();
  expect(screen.getByRole('link', { name: 'TEST-current source' })).toBeInTheDocument();
});
it('cannot apply a pending actor resolution to another source', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof api.resolveWorkActor>>) => void;
  vi.spyOn(api, 'resolveWorkActor').mockReturnValue(new Promise(yes => { resolve = yes; }));
  vi.mocked(api.fetchWorkSources).mockResolvedValue([source(), { ...source(), id: 'TEST-other-source', name: 'TEST-other source' }]);
  render(<WorkSources />);await addSource();await selectSource();fireEvent.change(screen.getByLabelText('Person', { selector: 'input,select,textarea' }), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));
  fireEvent.click(screen.getByRole('link', { name: 'TEST-other source' }));
  await act(async () => resolve({ status: 'FOUND', actors: [{ providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST-person' }], detail: null }));
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
  expect(screen.queryByLabelText('Resolved source person', { selector: 'input,select,textarea' })).toBeNull();
});
it('does not offer unsupported account authentication', async () => {
  vi.mocked(accounts.fetchProviders).mockResolvedValue([{ ...account(), authKind: 'basic' }]);
  render(<WorkSources />);await addSource();expect(within(await screen.findByLabelText('Tracker account', { selector: 'input,select,textarea' })).getAllByRole('option')).toHaveLength(1);
});
it('does not offer unavailable repositories', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([{ ...repository, enabled: false }]);
  render(<WorkSources />);await addSource();fireEvent.change(await screen.findByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  expect(within(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' })).getAllByRole('option')).toHaveLength(1);
});
it('does not offer another forge origin for a forge source', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([{ ...repository, forgeOrigin: 'https://TEST-other.invalid' }]);
  render(<WorkSources />);await addSource();fireEvent.change(await screen.findByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  expect(within(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' })).getAllByRole('option')).toHaveLength(1);
});
it('does not offer another platform on the same origin', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([{ ...repository, scmType: 'gitlab' }]);
  render(<WorkSources />);await addSource();fireEvent.change(await screen.findByLabelText('Tracker account', { selector: 'input,select,textarea' }), { target: { value: 'TEST-github' } });
  expect(within(screen.getByLabelText('Target repository', { selector: 'input,select,textarea' })).getAllByRole('option')).toHaveLength(1);
});
it('ignores a superseded list error', async () => {
  let reject!: (error: Error) => void;
  vi.mocked(api.fetchWorkSources).mockReturnValueOnce(new Promise((_yes, no) => { reject = no; })).mockResolvedValue([source()]);
  render(<WorkSources />);fireEvent.click(screen.getByRole('button', { name: 'Refresh work sources' }));
  await screen.findByRole('link', { name: 'TEST-source name' });await act(async () => reject(new Error('TEST-stale-error')));
  expect(screen.queryByRole('alert')).toBeNull();
});
it('does not infer a selection from multiple FOUND actors', async () => {
  vi.spyOn(api, 'resolveWorkActor').mockResolvedValue({ status: 'FOUND', actors: [
    { providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST-person' },
    { providerUserId: '900456', handle: 'TEST-other', displayName: 'TEST-other' }], detail: null });
  render(<WorkSources />);await addSource();await selectSource();fireEvent.change(screen.getByLabelText('Person', { selector: 'input,select,textarea' }), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));await screen.findByLabelText('Resolved source person', { selector: 'input,select,textarea' });
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
});
