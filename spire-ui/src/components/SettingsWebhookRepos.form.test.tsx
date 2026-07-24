import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import * as api from '../api';

const provider = (over: Partial<api.ProviderView>): api.ProviderView => ({
  id: 'p1', name: 'Acme Bot', type: 'github', baseUrl: 'https://api.github.com', workspace: 'acme',
  authKind: 'bearer', authUsername: null, hasSecret: true, botAccountId: 'b1', enabled: true,
  authors: [], conversationLevel: null, createdAt: '2026-07-23T00:00:00Z', ...over,
});

describe('WebhookRepoFormModal — provider picker', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([]);
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      provider({ id: 'p1', name: 'Acme Bot', type: 'github', workspace: 'acme' }),
      provider({ id: 'p2', name: 'Lab Bot', type: 'gitlab', workspace: 'my-team' }),
    ]);
  });

  it('lists registered providers and fixes the owner for repo scope', async () => {
    render(<SettingsWebhookRepos />);
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    // both registered providers are offered
    await waitFor(() => expect(screen.getByRole('option', { name: /Acme Bot · github · acme/ })).toBeInTheDocument());
    expect(screen.getByRole('option', { name: /Lab Bot · gitlab · my-team/ })).toBeInTheDocument();
    // repo scope shows the fixed owner prefix from the first provider's workspace
    expect(screen.getByText('acme/')).toBeInTheDocument();
  });

  it('shows an empty state when no providers are registered', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    render(<SettingsWebhookRepos />);
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await waitFor(() => expect(screen.getByText(/register a provider first/i)).toBeInTheDocument());
  });

  it('verifies the repository via the selected provider', async () => {
    const spy = vi.spyOn(api, 'verifyRepo').mockResolvedValue({ ok: true, detail: null });
    render(<SettingsWebhookRepos />);
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await screen.findByText('acme/');
    fireEvent.change(screen.getByPlaceholderText('repo-name'), { target: { value: 'widgets' } });
    fireEvent.click(screen.getByRole('button', { name: /verify/i }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith('p1', 'acme/widgets'));
    expect(await screen.findByText(/repository found/i)).toBeInTheDocument();
  });
});
