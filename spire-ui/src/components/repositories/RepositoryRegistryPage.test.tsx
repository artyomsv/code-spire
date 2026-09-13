import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import * as accounts from '../../api';
import * as api from './repositoriesApi';
import RepositoryRegistryPage from './RepositoryRegistryPage';

const provider = (id: string, role: accounts.ProviderRole, baseUrl = 'https://forge.example.test/api/v4'): accounts.ProviderView => ({
  id, name: id, role, baseUrl, type: 'gitlab', workspace: 'TEST-legacy', enabled: true,
  authKind: 'bearer', authUsername: null, hasSecret: true, botAccountId: `TEST-id-${id}`, botUsername: id,
  authors: [], conversationLevel: null, createdAt: '', lastCheckAt: null, lastCheckOk: null, lastCheckError: null,
});
const repo: api.Repository = {
  id: 'TEST-repo-id', scmType: 'gitlab', forgeOrigin: 'https://forge.example.test', workspace: 'TEST-group/nested',
  slug: 'TEST-repo', enabled: true, revision: 3,
  reviewer: { id: 'TEST-reviewer', name: 'TEST-reviewer', role: 'REVIEWER', handle: 'TEST-review-bot', state: 'configured' },
  factory: { id: 'TEST-factory', name: 'TEST-factory', role: 'FACTORY', handle: null, state: 'disabled' },
};

describe('Repository registry', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(accounts, 'fetchProviders').mockResolvedValue([provider('TEST-reviewer', 'REVIEWER'), provider('TEST-factory', 'FACTORY'),
      provider('TEST-wrong-host', 'REVIEWER', 'https://other.example.test'), provider('TEST-context', 'CONTEXT')]);
    vi.spyOn(api, 'fetchRepositories').mockResolvedValue([repo]);
    vi.spyOn(api, 'fetchRepositoryKinds').mockResolvedValue(['gitlab']);
    vi.spyOn(api, 'fetchPendingMappings').mockResolvedValue([]);
    vi.spyOn(api, 'saveRepository').mockResolvedValue(repo);
  });

  it('shows workspace and the selected reviewer and disabled factory', async () => {
    render(<RepositoryRegistryPage />);
    expect(await screen.findByText('TEST-group/nested')).toBeInTheDocument();
    expect(screen.getByText('TEST-reviewer (@TEST-review-bot) · configured')).toBeInTheDocument();
    expect(screen.getByText('TEST-factory · disabled')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'TEST-repo' }));
    expect(screen.getByRole('textbox', { name: 'Workspace' })).toHaveValue('TEST-group/nested');
    expect(screen.getByRole('combobox', { name: 'REVIEWER account' })).toHaveValue('TEST-reviewer');
    expect(screen.getByRole('combobox', { name: 'FACTORY account' })).toHaveValue('TEST-factory');
  });

  it('offers only same-origin accounts of the chosen role and saves explicit ids', async () => {
    render(<RepositoryRegistryPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'TEST-repo' }));
    const reviewer = screen.getByRole('combobox', { name: 'REVIEWER account' });
    expect(within(reviewer).queryByRole('option', { name: 'TEST-wrong-host' })).not.toBeInTheDocument();
    expect(within(reviewer).queryByRole('option', { name: 'TEST-factory' })).not.toBeInTheDocument();
    expect(within(reviewer).queryByRole('option', { name: 'TEST-context' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save repository' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(expect.objectContaining({
      workspace: 'TEST-group/nested', reviewerAccountId: 'TEST-reviewer', factoryAccountId: 'TEST-factory',
    }), repo));
  });

  it('keeps a stale-save error visible without pretending it saved', async () => {
    vi.mocked(api.saveRepository).mockRejectedValue(new Error('Repository changed; reload before saving'));
    render(<RepositoryRegistryPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'TEST-repo' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save repository' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('reload before saving');
    expect(screen.getByRole('heading', { name: 'Repository details' })).toBeInTheDocument();
  });

  it('can register an unbound repository before accounts exist', async () => {
    vi.mocked(api.fetchRepositories).mockResolvedValue([]); vi.mocked(accounts.fetchProviders).mockResolvedValue([]);
    render(<RepositoryRegistryPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Register repository' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Forge kind' }), { target: { value: 'gitlab' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Forge origin' }), { target: { value: repo.forgeOrigin } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Workspace' }), { target: { value: repo.workspace } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Repository slug' }), { target: { value: repo.slug } });
    fireEvent.click(screen.getByRole('button', { name: 'Save repository' }));
    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(expect.objectContaining({ reviewerAccountId: null, factoryAccountId: null }), null));
  });

  it('names the pending registration and links only a matching repository', async () => {
    const pending: api.PendingMapping = { registrationId: 'TEST-registration', revision: 8, scmType: 'gitlab', forgeOrigin: null,
      target: 'TEST-group/nested/TEST-repo', problem: 'conflicting_forge_origins' };
    vi.mocked(api.fetchPendingMappings).mockResolvedValue([pending]);
    vi.spyOn(api, 'linkMapping').mockResolvedValue(undefined);
    render(<RepositoryRegistryPage />);
    fireEvent.click(await screen.findByRole('button', { name: `Link to ${repo.forgeOrigin}/${pending.target}` }));
    await waitFor(() => expect(api.linkMapping).toHaveBeenCalledWith(pending, repo.id));
    await waitFor(() => expect(screen.queryByText(/Registration: TEST-registration/)).not.toBeInTheDocument());
  });
});
