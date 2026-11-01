vi.mock('../actorsApi', async importOriginal => ({ ...await importOriginal<typeof import('../actorsApi')>(), fetchActors: vi.fn().mockResolvedValue({ revision: 0, actors: [] }) }));
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import * as accounts from '../../api';
import * as api from './repositoriesApi';
import RepositoryRegistryPage from './RepositoryRegistryPage';
import { MemoryRouter, Link } from 'react-router';
import { queryWrapper } from '../../test/queryWrapper';
import * as sources from '../work-items/workSourcesApi';
import * as build from './factory/buildDefaultsApi';
import { buildSetup } from './factory/factoryFixtures';

const provider = (id: string, role: accounts.ProviderRole, baseUrl = 'https://forge.example.test/api/v4'): accounts.ProviderView => ({
  id, name: id, role, baseUrl, type: 'gitlab', enabled: true,
  authKind: 'bearer', authUsername: null, hasSecret: true, botAccountId: `TEST-id-${id}`, botUsername: id,
  authors: [], conversationLevel: null, createdAt: '', lastCheckAt: null, lastCheckOk: null, lastCheckError: null,
});
const repo: api.Repository = {
  id: 'TEST-repo-id', scmType: 'gitlab', forgeOrigin: 'https://forge.example.test', workspace: 'TEST-group/nested',
  slug: 'TEST-repo', enabled: true, revision: 3, reviewerMayApprove: true, reviewLlmProviderId: null, reviewHarness: null,
  reviewer: { id: 'TEST-reviewer', name: 'TEST-reviewer', role: 'REVIEWER', handle: 'TEST-review-bot', state: 'configured' },
  factory: { id: 'TEST-factory', name: 'TEST-factory', role: 'FACTORY', handle: null, state: 'disabled' },
};
const llmProvider = (id: string, enabled: boolean, isDefault = false): accounts.LlmProviderView => ({
  id, name: id, type: 'openai', baseUrl: 'https://llm.example.test', model: `TEST-model-${id}`, temperature: 0.2,
  maxTokens: null, hasApiKey: true, enabled, isDefault, createdAt: '', lastCheckAt: null, lastCheckOk: null, lastCheckError: null,
});

describe('Repository registry', () => {
  it('uses a text link and shared account column and keeps repair labels concise', async () => {
    vi.mocked(api.fetchPendingMappings).mockResolvedValue([{ registrationId: 'TEST-registration', revision: 8, scmType: 'gitlab',
      forgeOrigin: null, target: `${repo.workspace}/${repo.slug}`, problem: 'registration_origin_unknown' }]);
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    const name = await screen.findByRole('link', { name: repo.slug });
    expect(name).toHaveClass('mono', 'nowrap');
    expect(name).not.toHaveClass('btn');
    expect(screen.getByRole('columnheader', { name: 'Accounts' })).toBeInTheDocument();
    expect(name.closest('tr')?.querySelector('.serving-pair')).not.toBeNull();
    const pending = screen.getByRole('region', { name: 'Mappings needing attention' });
    expect(await within(pending).findByRole('button', { name: 'Link repository' })).toHaveTextContent(/^Link repository$/);
    expect(pending).not.toHaveTextContent('TEST-registration');
  });

  it('prefills registration from Attention while the repository page is already mounted', async () => {
    const query = new URLSearchParams({ register: 'true', scmType: repo.scmType, forgeOrigin: repo.forgeOrigin,
      workspace: repo.workspace, slug: 'TEST-new', registration: 'TEST-registration' });
    render(<MemoryRouter initialEntries={['/settings/repositories']}>
      <Link to={`/settings/repositories?${query}`}>TEST Attention Register</Link><RepositoryRegistryPage />
    </MemoryRouter>, { wrapper: queryWrapper() });
    expect(await screen.findByRole('link', { name: 'TEST-repo' })).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Workspace' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: 'TEST Attention Register' }));
    expect(await screen.findByRole('textbox', { name: 'Workspace' })).toHaveValue(repo.workspace);
    expect(screen.getByRole('textbox', { name: 'Forge origin' })).toHaveValue(repo.forgeOrigin);
    expect(screen.getByRole('textbox', { name: 'Repository slug' })).toHaveValue('TEST-new');
    // The id is carried in its own `mono` element, so the sentence spans two nodes.
    expect(screen.getByText('TEST-registration')).toHaveClass('mono');
    expect(screen.getByText(/Incoming registration:/)).toHaveTextContent('Incoming registration: TEST-registration');
  });
  it('keeps repository registration available through a gateway outage and retries hooks', async () => {
    vi.mocked(accounts.fetchWebhookRepos).mockRejectedValueOnce(new Error('TEST-gateway down')).mockResolvedValue([]);
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    expect(await screen.findByRole('alert')).toHaveTextContent('TEST-gateway down');
    fireEvent.click(await screen.findByRole('button', { name: 'Register repository' }));
    expect(screen.getByRole('textbox', { name: 'Workspace' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading webhooks' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(accounts, 'fetchWebhookRepos').mockResolvedValue([]);
    vi.spyOn(accounts, 'fetchProviders').mockResolvedValue([provider('TEST-reviewer', 'REVIEWER'), provider('TEST-factory', 'FACTORY'),
      provider('TEST-wrong-host', 'REVIEWER', 'https://other.example.test'), provider('TEST-context', 'CONTEXT')]);
    vi.spyOn(api, 'fetchRepositories').mockResolvedValue([repo]);
    vi.spyOn(api, 'fetchRepositoryKinds').mockResolvedValue(['gitlab']);
    vi.spyOn(api, 'fetchPendingMappings').mockResolvedValue([]);
    vi.spyOn(api, 'saveRepository').mockResolvedValue(repo);
    vi.spyOn(accounts, 'fetchLlmProviders').mockResolvedValue([llmProvider('TEST-default-llm', true, true),
      llmProvider('TEST-other-llm', true), llmProvider('TEST-off-llm', false)]);
  });

  it('shows workspace and the selected reviewer and disabled factory', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    expect(await screen.findByText('TEST-group/nested')).toBeInTheDocument();
    expect(screen.getByText('Reviewer: TEST-reviewer (@TEST-review-bot) · configured')).toBeInTheDocument();
    expect(screen.getByText('Factory: TEST-factory · disabled')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(within(screen.getByRole('region', { name: 'Identity' })).getByText('TEST-group/nested')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'REVIEWER account' })).toHaveValue('TEST-reviewer');
    expect(screen.getByRole('combobox', { name: 'FACTORY account' })).toHaveValue('TEST-factory');
  });

  it('offers only same-origin accounts of the chosen role and saves explicit ids', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    const reviewer = screen.getByRole('combobox', { name: 'REVIEWER account' });
    expect(within(reviewer).queryByRole('option', { name: 'TEST-wrong-host' })).not.toBeInTheDocument();
    expect(within(reviewer).queryByRole('option', { name: 'TEST-factory' })).not.toBeInTheDocument();
    expect(within(reviewer).queryByRole('option', { name: 'TEST-context' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('combobox', { name: 'FACTORY account' }), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(expect.objectContaining({
      workspace: 'TEST-group/nested', reviewerAccountId: 'TEST-reviewer', factoryAccountId: null,
    }), repo));
  });

  it('saves the reviewer-may-approve setting, on by default', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    const setting = screen.getByLabelText('Reviewer may approve');
    expect(setting).toBeChecked();
    fireEvent.click(setting);
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(expect.objectContaining({ reviewerMayApprove: false }), repo));
  });

  /** Harness reviews, decision 1: the global default, or one enabled provider; a switched-off one is never offered. */
  it('offers the global default and the enabled providers to review with, and saves the choice', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    const reviewWith = await screen.findByRole('combobox', { name: 'Review with' });
    expect(await within(reviewWith).findByRole('option', { name: 'Global default (TEST-default-llm)' })).toBeInTheDocument();
    expect(reviewWith).toHaveDisplayValue('Global default (TEST-default-llm)');
    expect(within(reviewWith).getByRole('option', { name: 'TEST-other-llm' })).toBeInTheDocument();
    expect(within(reviewWith).queryByRole('option', { name: /TEST-off-llm/ })).not.toBeInTheDocument();
    fireEvent.change(reviewWith, { target: { value: 'TEST-other-llm' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(
      expect.objectContaining({ reviewLlmProviderId: 'TEST-other-llm' }), repo));
  });

  /** A repository already naming a switched-off provider shows it, and a save keeps it: nothing changes the payer silently. */
  it('shows a named provider that is switched off, selected, and saves it unchanged', async () => {
    const named = { ...repo, reviewLlmProviderId: 'TEST-off-llm' };
    vi.mocked(api.fetchRepositories).mockResolvedValue([named]);
    vi.mocked(api.saveRepository).mockResolvedValue(named);
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    const reviewWith = await screen.findByRole('combobox', { name: 'Review with' });
    expect(await within(reviewWith).findByRole('option', { name: 'TEST-off-llm (switched off)' })).toBeInTheDocument();
    expect(reviewWith).toHaveValue('TEST-off-llm');
    fireEvent.click(screen.getByLabelText('Enabled'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(
      expect.objectContaining({ reviewLlmProviderId: 'TEST-off-llm' }), named));
  });

  /** Review of PR 365, M-1: a failed read says so and can be retried; it never claims no default is set. */
  it('says the provider list failed to load, offers a retry, and claims no default only after a read', async () => {
    vi.mocked(accounts.fetchLlmProviders).mockRejectedValueOnce(new Error('TEST-llm down'));
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(await screen.findByText(/TEST-llm down/)).toBeInTheDocument();
    const reviewWith = screen.getByRole('combobox', { name: 'Review with' });
    expect(within(reviewWith).queryByRole('option', { name: /none set/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading LLM providers' }));
    expect(await within(reviewWith).findByRole('option', { name: 'Global default (TEST-default-llm)' })).toBeInTheDocument();
    expect(screen.queryByText(/TEST-llm down/)).not.toBeInTheDocument();
  });

  it('keeps a stale-save error visible without pretending it saved', async () => {
    vi.mocked(api.saveRepository).mockRejectedValue(new Error('Repository changed; reload before saving'));
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Enabled'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('reload before saving');
    expect(screen.getByLabelText('Enabled')).not.toBeChecked();
    expect(screen.getByText('Unsaved changes', { selector: '.settings-state' })).toBeInTheDocument();
  });

  /** The operator's finding (2026-10-16): edit opened a second side panel over the first. */
  it('edits a repository in a Settings tab of the same panel, never a second panel', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    expect(screen.queryByRole('button', { name: 'Edit repository and accounts' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(screen.getByLabelText('Reviewer may approve')).toBeInTheDocument();
    // Identity is read-only text, not locked inputs.
    expect(screen.queryByRole('textbox', { name: 'Workspace' })).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Forge kind' })).not.toBeInTheDocument();
  });

  it('offers Save only after a change, and Discard puts the saved values back', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Reviewer may approve'));
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    expect(screen.getByText('Unsaved changes', { selector: '.settings-state' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.getByLabelText('Reviewer may approve')).toBeChecked();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(api.saveRepository).not.toHaveBeenCalled();
  });

  it('keeps an unsaved change while the operator looks at another tab', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Reviewer may approve'));
    fireEvent.click(screen.getByRole('tab', { name: 'Details' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(screen.getByLabelText('Reviewer may approve')).not.toBeChecked();
  });

  /** Review M-1 (2026-10-20): an edit made while a save was in flight was overwritten by its answer. */
  it('locks every control and the close actions until a save settles', async () => {
    let settle: (saved: api.Repository) => void = () => {};
    vi.mocked(api.saveRepository).mockReturnValue(new Promise(resolve => { settle = resolve; }));
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Enabled'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('button', { name: 'Saving…' })).toBeDisabled();
    expect(screen.getByLabelText('Reviewer may approve')).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'REVIEWER account' })).toBeDisabled();
    expect(screen.getByRole('tab', { name: 'Details' })).toBeDisabled();
    for (const close of screen.getAllByRole('button', { name: 'Close' })) expect(close).toBeDisabled();
    settle({ ...repo, enabled: false, revision: 4 });
    await waitFor(() => expect(screen.getByLabelText('Reviewer may approve')).toBeEnabled());
    for (const close of screen.getAllByRole('button', { name: 'Close' })) expect(close).toBeEnabled();
  });

  /** Review L-1 (2026-10-20): the unsaved-changes dot was the only sign, and a screen reader skipped it. */
  it('tells assistive technology which tab holds unsaved changes', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Reviewer may approve'));
    fireEvent.click(screen.getByRole('tab', { name: 'Details' }));
    expect(screen.getByRole('tab', { name: 'Settings' })).toHaveAccessibleDescription('Unsaved changes');
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.getByRole('tab', { name: 'Settings' })).not.toHaveAccessibleDescription('Unsaved changes');
  });

  /** The operator's finding (2026-10-20): each tab switch left the previous tab's content behind. */
  it('shows only the chosen tab after the operator switches back and forth', async () => {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    for (const name of ['Settings', 'Details', 'Settings', 'Details']) fireEvent.click(screen.getByRole('tab', { name }));
    expect(screen.getAllByText('Selected accounts')).toHaveLength(1);
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    expect(screen.queryByText('Selected accounts')).not.toBeInTheDocument();
  });

  it('shows the saved values after a save, with nothing left to save', async () => {
    vi.mocked(api.saveRepository).mockResolvedValue({ ...repo, reviewerMayApprove: false, revision: 4 });
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Reviewer may approve'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Saved')).toBeInTheDocument();
    expect(screen.getByLabelText('Reviewer may approve')).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    fireEvent.click(screen.getByRole('tab', { name: 'Details' }));
    expect(screen.getByText('Reviewer may approve: off')).toBeInTheDocument();
  });

  it('can register an unbound repository before accounts exist', async () => {
    vi.mocked(api.fetchRepositories).mockResolvedValue([]); vi.mocked(accounts.fetchProviders).mockResolvedValue([]);
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('button', { name: 'Register repository' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Forge kind' }), { target: { value: 'gitlab' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Forge origin' }), { target: { value: repo.forgeOrigin } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Workspace' }), { target: { value: repo.workspace } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Repository slug' }), { target: { value: repo.slug } });
    fireEvent.click(screen.getByRole('button', { name: 'Save repository' }));
    // reviewerMayApprove: a NEW repository gets the default (mutation check t10-b: the edit-form test
    // starts from a saved value, so only this registration path sees the default).
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(expect.objectContaining({ reviewerAccountId: null, factoryAccountId: null, reviewerMayApprove: true }), null));
  });

  it('names the pending registration and links only a matching repository', async () => {
    const pending: api.PendingMapping = { registrationId: 'TEST-registration', revision: 8, scmType: 'gitlab', forgeOrigin: null,
      target: 'TEST-group/nested/TEST-repo', problem: 'conflicting_forge_origins' };
    vi.mocked(api.fetchPendingMappings).mockResolvedValue([pending]);
    vi.spyOn(api, 'linkMapping').mockResolvedValue(undefined);
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('button', { name: 'Link repository' }));
    await waitFor(() => expect(api.linkMapping).toHaveBeenCalledWith(pending, repo.id));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Link repository' })).not.toBeInTheDocument());
  });

  it('repairs a gateway registration at its owner so new deliveries carry the selected origin', async () => {
    const pending: api.PendingMapping = { registrationId: 'TEST-registration', revision: 8, scmType: 'gitlab',
      forgeOrigin: null, target: `${repo.workspace}/${repo.slug}`, problem: 'registration_origin_unknown' };
    const hook: accounts.WebhookRepoView = { id: pending.registrationId, providerType: 'gitlab',
      scope: 'repo', target: pending.target, webhookKey: 'TEST-retained-key', hasSecret: true, enabled: true,
      forgeOrigin: null, repositoryId: null, eventKind: 'REVIEWER', createdAt: '' };
    vi.mocked(api.fetchPendingMappings).mockResolvedValue([pending]);
    vi.mocked(accounts.fetchWebhookRepos).mockResolvedValue([hook]);
    const historyLink = vi.spyOn(api, 'linkMapping');
    const update = vi.spyOn(accounts, 'updateWebhookRepo').mockResolvedValue({ ...hook, repositoryId: repo.id, forgeOrigin: repo.forgeOrigin });
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('button', { name: 'Link repository' }));
    await waitFor(() => expect(update).toHaveBeenCalledWith(hook.id, expect.objectContaining({
      repositoryId: repo.id, forgeOrigin: repo.forgeOrigin, eventKind: 'REVIEWER', target: pending.target,
    })));
    expect(update.mock.calls[0][1]).not.toHaveProperty('secret');
    expect(historyLink).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('link', { name: repo.slug }));
    expect(await screen.findByText('/webhooks/gitlab/TEST-retained-key')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create REVIEWER webhook' })).not.toBeInTheDocument();
  });

  // Step 6: the repositories are kept in the query cache, so the page opened again shows them while it reads.
  it('shows the repositories at once when the page opens again', async () => {
    const wrapper = queryWrapper();
    const first = render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper });
    await screen.findByRole('link', { name: repo.slug });
    first.unmount();
    vi.mocked(api.fetchRepositories).mockReturnValue(new Promise(() => {}));
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper });
    expect(await screen.findByRole('link', { name: repo.slug })).toBeInTheDocument();
  });

  // Review of PR 358, M-1: the server derives a source's enabled state from its repository's, so the column must re-read.
  it('reads the work sources again after a repository save', async () => {
    vi.spyOn(sources, 'fetchWorkSources').mockResolvedValue([]);
    vi.mocked(api.saveRepository).mockResolvedValue({ ...repo, enabled: false, revision: 4 });
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    await waitFor(() => expect(sources.fetchWorkSources).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Enabled'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Saved')).toBeInTheDocument();
    await waitFor(() => expect(sources.fetchWorkSources).toHaveBeenCalledTimes(2));
  });

  // A read of a key with no data yet is joined, not restarted, by invalidateQueries: its answer predates the save.
  it('reads the work sources again after a save made before they first answered', async () => {
    let first!: (value: sources.WorkSource[]) => void;
    vi.spyOn(sources, 'fetchWorkSources').mockReturnValueOnce(new Promise(done => { first = done; })).mockResolvedValue([]);
    vi.mocked(api.saveRepository).mockResolvedValue({ ...repo, enabled: false, revision: 4 });
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.click(screen.getByLabelText('Enabled'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Saved')).toBeInTheDocument();
    await waitFor(() => expect(sources.fetchWorkSources).toHaveBeenCalledTimes(2));
    await act(async () => first([]));
  });

  it('refuses to repair a registration whose current forge or coordinates changed', async () => {
    const pending: api.PendingMapping = { registrationId: 'TEST-registration', revision: 8, scmType: 'gitlab',
      forgeOrigin: null, target: `${repo.workspace}/${repo.slug}`, problem: 'registration_origin_unknown' };
    const hook: accounts.WebhookRepoView = { id: pending.registrationId, providerType: 'gitlab',
      scope: 'repo', target: pending.target, webhookKey: 'TEST-retained-key', hasSecret: true, enabled: true,
      forgeOrigin: null, repositoryId: null, eventKind: 'REVIEWER', createdAt: '' };
    vi.mocked(api.fetchPendingMappings).mockResolvedValue([pending]);
    const update = vi.spyOn(accounts, 'updateWebhookRepo');
    const historyLink = vi.spyOn(api, 'linkMapping');
    for (const changed of [{ providerType: 'github' }, { scope: 'org' as const },
      { target: 'TEST-other/TEST-repo' }, { forgeOrigin: 'https://other.example.test' }]) {
      vi.mocked(accounts.fetchWebhookRepos).mockResolvedValue([{ ...hook, ...changed }]);
      const page = render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
      fireEvent.click(await screen.findByRole('button', { name: 'Link repository' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Registration changed');
      expect(update).not.toHaveBeenCalled();
      expect(historyLink).not.toHaveBeenCalled();
      expect(screen.getByRole('button', { name: 'Link repository' })).toBeInTheDocument();
      page.unmount();
    }
  });
});

/** Harness reviews, step 5: "Review with" offers a harness beside the API providers, never both at once. */
describe('Review with a harness', () => {
  const seat: api.ReviewHarness = { harness: 'codex', model: 'TEST-model', effort: 'high', payWith: 'SUBSCRIPTION' };
  const catalogue: build.HarnessModels = { status: 'OK', offered: [{ slug: 'TEST-model', displayName: 'TEST-model',
    defaultEffort: 'medium', efforts: ['low', 'medium', 'high'], visible: true, priority: 1 }] };

  beforeEach(() => {
    vi.spyOn(accounts, 'fetchWebhookRepos').mockResolvedValue([]);
    vi.spyOn(accounts, 'fetchProviders').mockResolvedValue([provider('TEST-reviewer', 'REVIEWER'), provider('TEST-factory', 'FACTORY')]);
    vi.spyOn(api, 'fetchRepositories').mockResolvedValue([repo]);
    vi.spyOn(api, 'fetchRepositoryKinds').mockResolvedValue(['gitlab']);
    vi.spyOn(api, 'fetchPendingMappings').mockResolvedValue([]);
    vi.spyOn(api, 'saveRepository').mockResolvedValue(repo);
    vi.spyOn(accounts, 'fetchLlmProviders').mockResolvedValue([llmProvider('TEST-default-llm', true, true),
      llmProvider('TEST-other-llm', true)]);
    vi.spyOn(build, 'buildDefaults').mockResolvedValue(buildSetup());
    vi.spyOn(build, 'buildOptions').mockResolvedValue({ harnesses: ['codex'],
      reportedTypes: { codex: ['INPUT', 'CACHED_INPUT', 'CACHE_WRITE', 'OUTPUT', 'REASONING'] }, models: { codex: catalogue } });
    vi.spyOn(accounts, 'fetchLlmModels').mockResolvedValue([
      { id: 'TEST-model-id', type: 'openai', name: 'TEST-model', label: 'TEST model', pricingMode: 'METERED', rates: { INPUT: 100, OUTPUT: 200 },
        outputTokenParam: 'MAX_TOKENS', supportsTemperature: true, reasoningEffort: null, extraParams: {}, enabled: true,
        createdAt: '2026-09-16T00:00:00Z', notBilled: ['CACHED_INPUT', 'CACHE_WRITE', 'REASONING'] },
    ]);
  });

  async function openSettings() {
    render(<MemoryRouter><RepositoryRegistryPage /></MemoryRouter>, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('link', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Settings' }));
    return screen.findByRole('combobox', { name: 'Review with' });
  }

  async function choose(name: string, value: string) {
    const select = await screen.findByRole('combobox', { name });
    await waitFor(() => expect(within(select).getAllByRole('option').some(option => (option as HTMLOptionElement).value === value)).toBe(true));
    fireEvent.change(select, { target: { value } });
    return select;
  }

  it('choosing Harness shows the harness, payment, model and thinking selects', async () => {
    const reviewWith = await openSettings();
    expect(screen.queryByRole('combobox', { name: 'Harness' })).not.toBeInTheDocument();
    expect(await within(reviewWith).findByRole('option', { name: 'Harness' })).toBeInTheDocument();
    fireEvent.change(reviewWith, { target: { value: 'harness' } });
    await choose('Harness', 'codex');
    await choose('Pay with', 'SUBSCRIPTION');
    await choose('Model', 'TEST-model');
    expect(await screen.findByRole('combobox', { name: 'Thinking level' })).toBeInTheDocument();
  });

  it('saving a harness review sends reviewHarness and no provider', async () => {
    vi.mocked(api.fetchRepositories).mockResolvedValue([{ ...repo, reviewLlmProviderId: 'TEST-other-llm' }]);
    const reviewWith = await openSettings();
    await within(reviewWith).findByRole('option', { name: 'Harness' });
    fireEvent.change(reviewWith, { target: { value: 'harness' } });
    await choose('Harness', 'codex');
    await choose('Pay with', 'SUBSCRIPTION');
    await choose('Model', 'TEST-model');
    await choose('Thinking level', 'high');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalled());
    const sent = vi.mocked(api.saveRepository).mock.calls[0][0];
    expect(sent.reviewLlmProviderId).toBeNull();
    expect(sent.reviewHarness?.harness).toBe('codex');
    expect(sent.reviewHarness).toEqual(seat);
  });

  it('choosing an API provider after Harness sends reviewHarness null', async () => {
    const onHarness = { ...repo, reviewHarness: seat };
    vi.mocked(api.fetchRepositories).mockResolvedValue([onHarness]);
    vi.mocked(api.saveRepository).mockResolvedValue(onHarness);
    await openSettings();
    await choose('Review with', 'TEST-other-llm');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(
      expect.objectContaining({ reviewLlmProviderId: 'TEST-other-llm', reviewHarness: null }), onHarness));
  });

  it('a saved harness review opens with its values selected', async () => {
    vi.mocked(api.fetchRepositories).mockResolvedValue([{ ...repo, reviewHarness: seat }]);
    const reviewWith = await openSettings();
    await waitFor(() => expect(reviewWith).toHaveValue('harness'));
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Harness' })).toHaveValue('codex'));
    expect(screen.getByRole('combobox', { name: 'Pay with' })).toHaveValue('SUBSCRIPTION');
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Model' })).toHaveValue('TEST-model'));
    expect(await screen.findByRole('combobox', { name: 'Thinking level' })).toHaveValue('high');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });
});
