import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsContextProviders from './SettingsContextProviders';
import * as api from '../api';

const account = (id: string, type: string, enabled = true): api.ProviderView => ({
  id, name: id, type, enabled, baseUrl: 'https://source.example.test',
  authKind: 'bearer', authUsername: null, hasSecret: true, botAccountId: 'bot', botUsername: null,
  authors: [], conversationLevel: null, role: 'CONTEXT', createdAt: '', lastCheckAt: null,
  lastCheckOk: null, lastCheckError: null,
});
const source: api.ContextProviderView = {
  id: 'source', name: 'Project source', type: 'jira', baseUrl: 'https://source.example.test',
  accountId: 'site-account', accountName: 'site-account', accountEnabled: true,
  projectKeys: 'ONE', enabled: true, createdAt: '', lastCheckAt: null, lastCheckOk: null, lastCheckError: null,
};
const renderPage = () => render(<MemoryRouter><SettingsContextProviders /></MemoryRouter>);
async function select(name: string, option: string) {
  fireEvent.click(await screen.findByRole('combobox', { name }));
  fireEvent.click(await screen.findByRole('option', { name: option }));
}
async function open() {
  renderPage();
  fireEvent.click(await screen.findByRole('button', { name: /add provider/i }));
}

describe('Context sources select accounts', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([account('site-account', 'atlassian'), account('forge-account', 'github')]);
    vi.spyOn(api, 'checkContextProvider').mockResolvedValue({ ok: true, account: 'bot', detail: null });
  });

  it('offers only compatible accounts and has no credential fields', async () => {
    await open();
    await select('Type', 'github-issues');
    fireEvent.click(await screen.findByRole('combobox', { name: 'Account' }));
    expect(await screen.findByRole('option', { name: 'forge-account · github · Context' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'site-account · atlassian · Context' })).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: /auth/i })).not.toBeInTheDocument();
    expect(document.querySelector('input[type=password]')).toBeNull();
    expect(screen.queryByText('Account email')).not.toBeInTheDocument();
  });

  it('explains an empty picker and links to registration', async () => {
    vi.mocked(api.fetchProviders).mockResolvedValue([]);
    await open();
    expect(await screen.findByText(/Register an account first/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Accounts' })).toHaveAttribute('href', '#/settings/accounts');
    expect(screen.queryByRole('combobox', { name: 'Account' })).not.toBeInTheDocument();
  });

  it('saves an account reference and source-specific keys without credentials', async () => {
    const save = vi.spyOn(api, 'createContextProvider').mockResolvedValue(source);
    await open();
    fireEvent.change(screen.getByPlaceholderText('Acme Jira'), { target: { value: 'Project source' } });
    await select('Account', 'site-account · atlassian · Context');
    fireEvent.change(screen.getByPlaceholderText('ACME, PROJ'), { target: { value: 'ONE' } });
    fireEvent.submit(screen.getByPlaceholderText('Acme Jira').closest('form')!);
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(save.mock.calls[0][0]).toEqual({ name: 'Project source', type: 'jira', baseUrl: 'https://source.example.test', accountId: 'site-account', projectKeys: 'ONE', enabled: true });
  });

  it('opens a migrated source with its account selected and can disable the source', async () => {
    vi.mocked(api.fetchContextProviders).mockResolvedValue([source]);
    const save = vi.spyOn(api, 'updateContextProvider').mockResolvedValue(source);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    expect(await screen.findByRole('combobox', { name: 'Account' })).toHaveTextContent('site-account');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Enabled' }));
    fireEvent.submit(screen.getByDisplayValue('Project source').closest('form')!);
    await waitFor(() => expect(save).toHaveBeenCalledWith('source', expect.objectContaining({ accountId: 'site-account', enabled: false })));
  });

  it('warns when an account is disabled', async () => {
    vi.mocked(api.fetchContextProviders).mockResolvedValue([{ ...source, accountEnabled: false }]);
    renderPage();
    expect(await screen.findByText('Account disabled')).toBeInTheDocument();
    expect(api.checkContextProvider).not.toHaveBeenCalled();
  });

  it('code offers both supported platforms and preserves the path allowlist', async () => {
    vi.mocked(api.fetchProviders).mockResolvedValue([account('site-account', 'atlassian'), account('forge-account', 'github'), account('lab-account', 'gitlab')]);
    await open();
    await select('Type', 'Repository code');
    fireEvent.click(await screen.findByRole('combobox', { name: 'Account' }));
    expect(await screen.findByRole('option', { name: 'forge-account · github · Context' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'lab-account · gitlab · Context' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'site-account · atlassian · Context' })).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText('src/main/, src/allowed/')).toBeInTheDocument();
  });
});
