import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import { MemoryRouter } from 'react-router';
import * as api from '../api';

/**
 * The page reads `?edit=` to deep-link an attention row to one registration, so it needs router
 * context — as it always has in the app, where it is rendered inside a route.
 */
const renderPage = () => render(<MemoryRouter><SettingsWebhookRepos /></MemoryRouter>);

const provider = (over: Partial<api.ProviderView>): api.ProviderView => ({
  id: 'p1', name: 'Acme Bot', type: 'github', baseUrl: 'https://api.github.com', workspace: 'acme',
  authKind: 'bearer', authUsername: null, hasSecret: true, botAccountId: 'b1', enabled: true,
  authors: [], conversationLevel: null, createdAt: '2026-07-23T00:00:00Z',
  lastCheckAt: null, lastCheckOk: null, lastCheckError: null, ...over,
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
    renderPage();
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    // open the provider dropdown, then assert both are offered
    fireEvent.click(await screen.findByRole('combobox', { name: /provider/i }));
    await waitFor(() => expect(screen.getByRole('option', { name: /Acme Bot · github · acme/ })).toBeInTheDocument());
    expect(screen.getByRole('option', { name: /Lab Bot · gitlab · my-team/ })).toBeInTheDocument();
    expect(screen.getByText('acme/')).toBeInTheDocument();
  });

  it('shows an empty state when no providers are registered', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    renderPage();
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await waitFor(() => expect(screen.getByText(/register a provider first/i)).toBeInTheDocument());
  });

  it('verifies the repository via the selected provider', async () => {
    const spy = vi.spyOn(api, 'verifyRepo').mockResolvedValue({ ok: true, detail: null });
    renderPage();
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await screen.findByText('acme/');
    fireEvent.change(screen.getByPlaceholderText('repo-name'), { target: { value: 'widgets' } });
    fireEvent.click(screen.getByRole('button', { name: /verify/i }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith('p1', 'acme/widgets'));
    expect(await screen.findByText(/repository found/i)).toBeInTheDocument();
  });

  it('shows the failure detail when verification fails', async () => {
    vi.spyOn(api, 'verifyRepo').mockResolvedValue({
      ok: false,
      detail: 'Repository not found, or the token cannot see it (HTTP 404).',
    });
    renderPage();
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await screen.findByText('acme/');
    fireEvent.change(screen.getByPlaceholderText('repo-name'), { target: { value: 'widgets' } });
    fireEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(
      await screen.findByText(/Repository not found, or the token cannot see it \(HTTP 404\)\./i),
    ).toBeInTheDocument();
  });

  it('drops a stale verify response after the slug changes while in flight', async () => {
    let resolveVerify: (value: api.RepoCheck) => void = () => {};
    const pending = new Promise<api.RepoCheck>((resolve) => {
      resolveVerify = resolve;
    });
    vi.spyOn(api, 'verifyRepo').mockReturnValue(pending);
    renderPage();
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    await screen.findByText('acme/');
    fireEvent.change(screen.getByPlaceholderText('repo-name'), { target: { value: 'widgets' } });
    fireEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByRole('button', { name: /verifying/i })).toBeInTheDocument();

    // The user edits the slug while the verify call is still in flight — this resets the indicator to idle.
    fireEvent.change(screen.getByPlaceholderText('repo-name'), { target: { value: 'widgets2' } });

    // The original (now-stale) request resolves after the reset; flush it and confirm it was dropped.
    await act(async () => {
      resolveVerify({ ok: true, detail: null });
      await pending;
    });

    expect(screen.queryByText(/repository found/i)).not.toBeInTheDocument();
  });
});
