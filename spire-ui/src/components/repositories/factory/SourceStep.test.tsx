import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import * as gateway from '../../../api';
import * as sources from '../../work-items/workSourcesApi';
import * as policies from '../../work-items/workPolicyApi';
import RepositoryFactory from './RepositoryFactory';
import { account, issueHook, policy, profile, repository, source } from './factoryFixtures';

const field = { selector: 'input,select,textarea' };
let changedHooks = vi.fn();
function renderFactory(options: { accounts?: gateway.ProviderView[]; hooks?: gateway.WebhookRepoView[]; unavailable?: boolean; repo?: typeof repository } = {}) {
  changedHooks = vi.fn();
  return render(<RepositoryFactory repository={options.repo ?? repository} accounts={options.accounts ?? [account(), account('gitlab'), account('atlassian')]}
    webhooks={{ hooks: options.hooks ?? [], unavailable: options.unavailable ?? false, changed: changedHooks }} onChanged={vi.fn()} />);
}
const step = (number: number) => screen.findByRole('listitem', { name: new RegExp(`^Step ${number}:`) });
beforeEach(() => {
  vi.spyOn(sources, 'fetchWorkSources').mockResolvedValue([source()]);
  vi.spyOn(sources, 'createWorkSource').mockResolvedValue(source());
  vi.spyOn(sources, 'editWorkSource').mockResolvedValue(source());
  vi.spyOn(sources, 'rescanWorkSource').mockResolvedValue();
  vi.spyOn(policies, 'profiles').mockResolvedValue([profile]);
  vi.spyOn(policies, 'policy').mockResolvedValue(policy());
  vi.spyOn(gateway, 'fetchWebhookRepos').mockResolvedValue([]);
});
async function addSource(options?: Parameters<typeof renderFactory>[0]) {
  vi.mocked(sources.fetchWorkSources).mockResolvedValue([]);
  renderFactory(options);
  fireEvent.click(within(await step(1)).getByRole('button', { name: 'Add source' }));
  return screen.getByRole('group', { name: 'Add where tickets come from' });
}

it('offers only the trackers this repository can take tickets from', async () => {
  const form = await addSource();
  expect(within(within(form).getByLabelText('Tracker', field)).getAllByRole('option').map(option => option.textContent)).toEqual(['GitHub', 'Jira']);
});
it('offers Jira alone to a repository whose forge has no issue source', async () => {
  const form = await addSource({ repo: { ...repository, scmType: 'bitbucket-cloud', forgeOrigin: 'https://bitbucket.example.test' } });
  expect(within(within(form).getByLabelText('Tracker', field)).getAllByRole('option').map(option => option.textContent)).toEqual(['Jira']);
});
it('offers only enabled bearer accounts of the tracker kind on this repository origin', async () => {
  const form = await addSource({ accounts: [account('github', { id: 'TEST-good', name: 'TEST-good' }), account('github', { id: 'TEST-disabled', name: 'TEST-disabled', enabled: false }),
    account('github', { id: 'TEST-elsewhere', name: 'TEST-elsewhere', baseUrl: 'https://TEST-other.invalid' }), account('github', { id: 'TEST-basic', name: 'TEST-basic', authKind: 'basic' }),
    account('gitlab', { id: 'TEST-gitlab', name: 'TEST-gitlab' })] });
  const options = within(within(form).getByLabelText('Tracker account', field)).getAllByRole('option').map(option => option.getAttribute('value'));
  expect(options).toEqual(['', 'TEST-good']);
});
it('explains a missing account and links to Accounts instead of an empty picker', async () => {
  const form = await addSource({ accounts: [account('github', { baseUrl: 'https://TEST-other.invalid' })] });
  expect(within(form).getByText(/No enabled GitHub account on https:\/\/github.example.test/)).toBeInTheDocument();
  expect(within(form).getByRole('link', { name: 'Add an account' })).toHaveAttribute('href', '#/settings/accounts');
  expect(within(form).getByRole('button', { name: 'Register work source' })).toBeDisabled();
});
it('distinguishes duplicate credential names in the create and edit account pickers', async () => {
  const accounts = [account('github', { name: 'TEST-shared name' }), account('github', { id: 'TEST-reviewer', name: 'TEST-shared name', role: 'REVIEWER' })];
  renderFactory({ accounts });
  const expectDistinct = (select: HTMLElement) => {
    expect(within(select).getByRole('option', { name: 'TEST-shared name · github · Factory' })).toHaveValue('TEST-github');
    expect(within(select).getByRole('option', { name: 'TEST-shared name · github · Reviewer' })).toHaveValue('TEST-reviewer');
  };
  fireEvent.click(within(await step(1)).getByRole('button', { name: 'Add another source' }));
  expectDistinct(within(screen.getByRole('group', { name: 'Add where tickets come from' })).getByLabelText('Tracker account', field));
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  fireEvent.click(screen.getByRole('button', { name: 'Edit TEST-source name' }));
  expectDistinct(within(screen.getByRole('group', { name: 'Edit TEST-source name' })).getByLabelText('Source account', field));
});
it('registers a forge source whose scope is the repository itself', async () => {
  const form = await addSource();
  expect(within(form).getByLabelText('Tracker repository', field)).toHaveAttribute('readonly');
  expect(within(form).getByLabelText('Source name', field)).toHaveValue('TEST-repo issues');
  fireEvent.change(within(form).getByLabelText('Tracker account', field), { target: { value: 'TEST-github' } });
  fireEvent.click(within(form).getByRole('button', { name: 'Register work source' }));
  await waitFor(() => expect(sources.createWorkSource).toHaveBeenCalledWith({ name: 'TEST-repo issues', type: 'GITHUB', origin: 'https://github.example.test',
    scope: 'TEST-owner/TEST-repo', repositoryId: repository.id, accountId: 'TEST-github', enabled: true }));
  expect(await screen.findByText('Work source TEST-source name registered.')).toBeInTheDocument();
});
it('maps a Jira project to this repository even though it lives on another forge', async () => {
  const form = await addSource();
  fireEvent.change(within(form).getByLabelText('Tracker', field), { target: { value: 'JIRA' } });
  fireEvent.change(within(form).getByLabelText('Tracker account', field), { target: { value: 'TEST-atlassian' } });
  fireEvent.change(within(form).getByLabelText('Jira project key', field), { target: { value: 'TEST' } });
  fireEvent.click(within(form).getByRole('button', { name: 'Register work source' }));
  await waitFor(() => expect(sources.createWorkSource).toHaveBeenCalledWith(expect.objectContaining({ type: 'JIRA', origin: 'https://atlassian.example.test', scope: 'TEST', repositoryId: repository.id })));
});
it('keeps every control locked while a source is saved', async () => {
  let finish!: (value: sources.WorkSource) => void;
  vi.mocked(sources.createWorkSource).mockReturnValue(new Promise(done => { finish = done; }));
  const form = await addSource();
  fireEvent.change(within(form).getByLabelText('Tracker account', field), { target: { value: 'TEST-github' } });
  fireEvent.click(within(form).getByRole('button', { name: 'Register work source' }));
  for (const control of form.querySelectorAll('input,select,button')) expect(control).toBeDisabled();
  expect(sources.createWorkSource).toHaveBeenCalledTimes(1);
  await act(async () => finish(source()));
});
it('does not let a disabled repository take a new source', async () => {
  vi.mocked(sources.fetchWorkSources).mockResolvedValue([]);
  renderFactory({ repo: { ...repository, enabled: false } });
  expect(within(await step(1)).getByRole('button', { name: 'Add source' })).toBeDisabled();
  expect(screen.getByText(/This repository is disabled/)).toBeInTheDocument();
});
it('preserves the configured enabled flag when the account is unavailable', async () => {
  vi.mocked(sources.fetchWorkSources).mockResolvedValue([source({ enabled: false, configuredEnabled: true })]);
  renderFactory({ accounts: [account('github', { enabled: false })] });
  expect(within(await step(1)).getByRole('button', { name: 'Scan TEST-source name now' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Edit TEST-source name' }));
  const form = screen.getByRole('group', { name: 'Edit TEST-source name' });
  expect(within(form).getByLabelText('Source enabled', field)).toBeChecked();
  expect(within(form).getByText(/enabled, but its account or repository is unavailable/)).toBeInTheDocument();
});
it('saves an edited source with its revision and requests a scan', async () => {
  vi.spyOn(sources, 'workCapabilities').mockResolvedValue({ operations: ['CANDIDATES', 'COMMENT'], detail: 'TEST-this deployment cannot attribute labels.' });
  renderFactory();
  fireEvent.click(within(await step(1)).getByRole('button', { name: 'Scan TEST-source name now' }));
  await waitFor(() => expect(sources.rescanWorkSource).toHaveBeenCalledWith('TEST-source'));
  fireEvent.click(await screen.findByRole('button', { name: 'Edit TEST-source name' }));
  const form = screen.getByRole('group', { name: 'Edit TEST-source name' });
  fireEvent.click(within(form).getByRole('button', { name: 'Check supported operations' }));
  expect(await within(form).findByText(/TEST-this deployment cannot attribute labels/)).toHaveTextContent('candidates, comment');
  fireEvent.change(within(form).getByLabelText('Edit source name', field), { target: { value: 'TEST-renamed' } });
  fireEvent.click(within(form).getByRole('button', { name: 'Save source' }));
  await waitFor(() => expect(sources.editWorkSource).toHaveBeenCalledWith(source(), { name: 'TEST-renamed', accountId: 'TEST-github', enabled: true }));
});
it('turns on instant updates with an issue webhook bound to the source and reveals its secret once', async () => {
  vi.spyOn(gateway, 'createWebhookRepo').mockResolvedValue({ repo: issueHook, secret: 'TEST-secret' });
  renderFactory();
  fireEvent.click(within(await step(1)).getByRole('button', { name: 'Turn on instant updates' }));
  await waitFor(() => expect(gateway.createWebhookRepo).toHaveBeenCalledWith({ providerType: 'github', forgeOrigin: repository.forgeOrigin,
    repositoryId: repository.id, eventKind: 'ISSUE', sourceId: 'TEST-source', scope: 'repo', target: 'TEST-owner/TEST-repo', enabled: true }));
  const reveal = await screen.findByRole('dialog', { name: 'Webhook secret' });
  expect(within(reveal).getByText(/subscribe to Issues and Issue comments/)).toBeInTheDocument();
  expect(changedHooks).toHaveBeenCalledWith([issueHook]);
});
it('does not create a second issue webhook when the first response was lost', async () => {
  vi.mocked(gateway.fetchWebhookRepos).mockResolvedValue([issueHook]);
  const create = vi.spyOn(gateway, 'createWebhookRepo');
  renderFactory();
  fireEvent.click(within(await step(1)).getByRole('button', { name: 'Turn on instant updates' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('already turned on');
  expect(create).not.toHaveBeenCalled();
});
it('shows instant updates as on when the issue webhook exists, and explains Jira has none', async () => {
  vi.mocked(sources.fetchWorkSources).mockResolvedValue([source(), source({ id: 'TEST-jira', name: 'TEST-jira source', type: 'JIRA', scope: 'TEST' })]);
  renderFactory({ hooks: [issueHook] });
  const first = await step(1);
  expect(within(first).getByText('instant updates on')).toBeInTheDocument();
  expect(within(first).getByText(/Jira is polled/)).toBeInTheDocument();
  expect(within(first).queryByRole('button', { name: 'Turn on instant updates' })).toBeNull();
});
it('cannot turn on instant updates while webhooks are unavailable', async () => {
  renderFactory({ unavailable: true });
  expect(within(await step(1)).getByRole('button', { name: 'Turn on instant updates' })).toBeDisabled();
});
