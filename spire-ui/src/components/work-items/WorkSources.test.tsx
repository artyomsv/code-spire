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
  vi.spyOn(accounts, 'fetchProviders').mockResolvedValue([account(), account('gitlab'), account('atlassian')]);
  vi.spyOn(repositories, 'fetchRepositories').mockResolvedValue([repository]);
  vi.spyOn(api, 'fetchWorkSources').mockResolvedValue([source()]);
  vi.spyOn(api, 'createWorkSource').mockResolvedValue(source());
  vi.spyOn(api, 'editWorkSource').mockResolvedValue(source());
  vi.spyOn(api, 'rescanWorkSource').mockResolvedValue();
  vi.spyOn(api, 'saveWorkActor').mockResolvedValue(source());
});
async function selectSource() { fireEvent.click(await screen.findByRole('link', { name: 'TEST-source name' })); }

it('styles both forms and keeps all their controls locked during a save', async () => {
  let finishCreate!: (value: api.WorkSource) => void, finishEdit!: (value: api.WorkSource) => void;
  vi.mocked(api.createWorkSource).mockReturnValue(new Promise(done => { finishCreate = done; }));
  vi.mocked(api.editWorkSource).mockReturnValue(new Promise(done => { finishEdit = done; }));
  render(<WorkSources />);
  const name = await screen.findByRole('link', { name: 'TEST-source name' });
  expect(name).toHaveClass('mono', 'nowrap');
  fireEvent.click(name);
  for (const label of ['Source name', 'Tracker', 'Tracker account', 'Target repository', 'Tracker repository', 'Edit source name', 'Source account', 'Person']) {
    expect(screen.getByLabelText(label).closest('label')).toHaveClass('field');
  }
  const create = screen.getByRole('group', { name: 'Add a work source' });
  const edit = screen.getByRole('group', { name: 'TEST-source name' });
  expect(create).toHaveStyle({ borderWidth: '0px', borderStyle: 'none' }); expect(edit).toHaveStyle({ borderWidth: '0px', borderStyle: 'none' });
  fireEvent.change(screen.getByLabelText('Source name'), { target: { value: 'TEST-create' } });
  fireEvent.change(screen.getByLabelText('Tracker account'), { target: { value: 'TEST-github' } });
  fireEvent.change(screen.getByLabelText('Target repository'), { target: { value: repository.id } });
  fireEvent.click(screen.getByRole('button', { name: 'Register work source' }));
  for (const element of create.querySelectorAll('input,select,button')) expect(element).toBeDisabled();
  expect(api.createWorkSource).toHaveBeenCalledTimes(1);
  await act(async () => finishCreate(source()));
  fireEvent.click(screen.getByRole('button', { name: 'Save source' }));
  for (const element of edit.querySelectorAll('input,select,button')) expect(element).toBeDisabled();
  expect(api.editWorkSource).toHaveBeenCalledTimes(1);
  await act(async () => finishEdit(source()));
});

it('registers a source with an explicit compatible account and repository', async () => {
  render(<WorkSources />);
  fireEvent.change(await screen.findByLabelText('Source name'), { target: { value: 'TEST-new source' } });
  fireEvent.change(screen.getByLabelText('Tracker account'), { target: { value: 'TEST-github' } });
  fireEvent.change(screen.getByLabelText('Target repository'), { target: { value: repository.id } });
  fireEvent.click(screen.getByRole('button', { name: 'Register work source' }));
  await waitFor(() => expect(api.createWorkSource).toHaveBeenCalledWith({ name: 'TEST-new source', type: 'GITHUB', origin: 'https://github.example.test', scope: 'TEST-owner/TEST-repo', repositoryId: repository.id, accountId: 'TEST-github', enabled: true }));
});
it('maps a Jira project to a repository on a different forge', async () => {
  render(<WorkSources />);
  fireEvent.change(await screen.findByLabelText('Tracker'), { target: { value: 'JIRA' } });
  fireEvent.change(screen.getByLabelText('Source name'), { target: { value: 'TEST-Jira source' } });
  fireEvent.change(screen.getByLabelText('Tracker account'), { target: { value: 'TEST-atlassian' } });
  fireEvent.change(screen.getByLabelText('Target repository'), { target: { value: repository.id } });
  fireEvent.change(screen.getByLabelText('Jira project key'), { target: { value: 'TEST' } });
  fireEvent.click(screen.getByRole('button', { name: 'Register work source' }));
  await waitFor(() => expect(api.createWorkSource).toHaveBeenCalledWith(expect.objectContaining({ type: 'JIRA', origin: 'https://atlassian.example.test', scope: 'TEST', repositoryId: repository.id })));
});
it('does not offer disabled or incompatible accounts', async () => {
  vi.mocked(accounts.fetchProviders).mockResolvedValue([account('github', false), account('gitlab'), account('atlassian')]);
  render(<WorkSources />);const select = await screen.findByLabelText('Tracker account');
  expect(within(select).getAllByRole('option')).toHaveLength(1);
  expect(screen.getByRole('button', { name: 'Register work source' })).toBeDisabled();
});
it('preserves the configured enabled flag when the account is disabled', async () => {
  vi.mocked(api.fetchWorkSources).mockResolvedValue([{ ...source(), enabled: false, configuredEnabled: true }]);
  vi.mocked(accounts.fetchProviders).mockResolvedValue([account('github', false)]);
  render(<WorkSources />);await selectSource();
  expect(screen.getByLabelText('Source enabled')).toBeChecked();
  expect(screen.getByText(/This source is enabled, but its account or repository is unavailable/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Request rescan' })).toBeDisabled();
});
it('requires explicit Jira account selection and saves the source revision', async () => {
  const initial = source('JIRA');vi.mocked(api.fetchWorkSources).mockResolvedValue([initial]);
  vi.spyOn(api, 'resolveWorkActor').mockResolvedValue({ status: 'SELECTION_REQUIRED', actors: [{ providerUserId: '900123', handle: '', displayName: 'TEST-person' }], detail: 'Select an account explicitly.' });
  render(<WorkSources />);await selectSource();
  fireEvent.change(screen.getByLabelText('Person'), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));
  const select = await screen.findByLabelText('Resolved source person');
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
  fireEvent.change(select, { target: { value: '900123' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save source person' }));
  await waitFor(() => expect(api.saveWorkActor).toHaveBeenCalledWith(initial, 'TEST-person', '900123'));
});
it('discards a resolved selection when the typed person changes', async () => {
  vi.spyOn(api, 'resolveWorkActor').mockResolvedValue({ status: 'FOUND', actors: [{ providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST-person' }], detail: null });
  render(<WorkSources />);await selectSource();
  fireEvent.change(screen.getByLabelText('Person'), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));
  await screen.findByLabelText('Resolved source person');
  fireEvent.change(screen.getByLabelText('Person'), { target: { value: 'TEST-someone-else' } });
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
});
it('requests a rescan and displays the reported capability limits', async () => {
  vi.spyOn(api, 'workCapabilities').mockResolvedValue({ operations: ['CANDIDATES', 'COMMENT'], detail: 'TEST-this deployment cannot attribute labels.' });
  render(<WorkSources />);await selectSource();fireEvent.click(screen.getByRole('button', { name: 'Request rescan' }));
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
  render(<WorkSources />);await selectSource();fireEvent.change(screen.getByLabelText('Person'), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));
  fireEvent.click(screen.getByRole('link', { name: 'TEST-other source' }));
  await act(async () => resolve({ status: 'FOUND', actors: [{ providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST-person' }], detail: null }));
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
  expect(screen.queryByLabelText('Resolved source person')).toBeNull();
});
it('does not offer unsupported account authentication', async () => {
  vi.mocked(accounts.fetchProviders).mockResolvedValue([{ ...account(), authKind: 'basic' }]);
  render(<WorkSources />);expect(within(await screen.findByLabelText('Tracker account')).getAllByRole('option')).toHaveLength(1);
});
it('does not offer unavailable repositories', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([{ ...repository, enabled: false }]);
  render(<WorkSources />);fireEvent.change(await screen.findByLabelText('Tracker account'), { target: { value: 'TEST-github' } });
  expect(within(screen.getByLabelText('Target repository')).getAllByRole('option')).toHaveLength(1);
});
it('does not offer another forge origin for a forge source', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([{ ...repository, forgeOrigin: 'https://TEST-other.invalid' }]);
  render(<WorkSources />);fireEvent.change(await screen.findByLabelText('Tracker account'), { target: { value: 'TEST-github' } });
  expect(within(screen.getByLabelText('Target repository')).getAllByRole('option')).toHaveLength(1);
});
it('does not offer another platform on the same origin', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([{ ...repository, scmType: 'gitlab' }]);
  render(<WorkSources />);fireEvent.change(await screen.findByLabelText('Tracker account'), { target: { value: 'TEST-github' } });
  expect(within(screen.getByLabelText('Target repository')).getAllByRole('option')).toHaveLength(1);
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
  render(<WorkSources />);await selectSource();fireEvent.change(screen.getByLabelText('Person'), { target: { value: 'TEST-person' } });
  fireEvent.click(screen.getByRole('button', { name: 'Resolve source person' }));await screen.findByLabelText('Resolved source person');
  expect(screen.getByRole('button', { name: 'Save source person' })).toBeDisabled();
});
