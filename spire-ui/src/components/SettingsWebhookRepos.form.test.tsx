import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createQueryClient } from '../queryClient';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import { MemoryRouter } from 'react-router';
import * as api from '../api';
import { queryWrapper } from '../test/queryWrapper';

const legacy: api.WebhookRepoView = { id: 'TEST-legacy', providerType: 'github', scope: 'org', target: 'TEST-org',
  webhookKey: 'TEST-preserved-key', hasSecret: true, enabled: true, createdAt: '', forgeOrigin: null, eventKind: 'REVIEWER' };
const renderPage = (route = '/settings/webhooks', wrapper = queryWrapper()) =>
  render(<MemoryRouter initialEntries={[route]}><SettingsWebhookRepos /></MemoryRouter>, { wrapper });

describe('Legacy webhook repair', () => {
  beforeEach(() => { vi.restoreAllMocks(); vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([legacy]); });

  it('directs new hooks to explicit repository registration', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Add webhook' }));
    expect(screen.getByRole('link', { name: 'Register a repository, then choose its webhook kinds.' })).toHaveAttribute('href', '#/settings/repositories');
    expect(screen.queryByRole('combobox', { name: /workspace/i })).not.toBeInTheDocument();
  });

  it('opens an organization deep link and repairs origin without rotating its secret', async () => {
    const update = vi.spyOn(api, 'updateWebhookRepo').mockResolvedValue({ ...legacy, forgeOrigin: 'https://api.github.com' });
    const rotate = vi.spyOn(api, 'rotateWebhookSecret');
    renderPage('/settings/webhooks?edit=TEST-legacy');
    fireEvent.change(await screen.findByRole('textbox', { name: 'Forge origin' }), { target: { value: 'https://api.github.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(update).toHaveBeenCalledWith(legacy.id, { providerType: 'github', scope: 'org', target: 'TEST-org', enabled: true, forgeOrigin: 'https://api.github.com' }));
    expect(rotate).not.toHaveBeenCalled();
  });

  it('keeps a failed repair open for retry', async () => {
    vi.spyOn(api, 'updateWebhookRepo').mockRejectedValue(new Error('TEST-gateway unavailable'));
    renderPage('/settings/webhooks?edit=TEST-legacy');
    fireEvent.change(await screen.findByRole('textbox', { name: 'Forge origin' }), { target: { value: 'https://api.github.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('TEST-gateway unavailable');
    expect(screen.getByRole('textbox', { name: 'Forge origin' })).toHaveValue('https://api.github.com');
  });
});

describe('Webhooks — the query cache (step 6)', () => {
  beforeEach(() => { vi.restoreAllMocks(); vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([legacy]); });

  it('shows the webhooks at once when the page opens again', async () => {
    const wrapper = queryWrapper();
    const first = renderPage('/settings/webhooks', wrapper);
    expect(await screen.findAllByText('TEST-org')).not.toHaveLength(0);
    first.unmount();
    vi.mocked(api.fetchWebhookRepos).mockReturnValue(new Promise(() => {}));
    renderPage('/settings/webhooks', wrapper);
    expect(await screen.findAllByText('TEST-org')).not.toHaveLength(0);
  });
});

describe('Webhooks — review of PR 359, M-2', () => {
  beforeEach(() => { vi.restoreAllMocks(); });

  // Another operator disabled the webhook; a save from the cached row would have turned it back on.
  it('opens a deep-linked webhook from this visit\'s read, not the cached row', async () => {
    const client = createQueryClient();
    client.setQueryData(api.webhookReposKey, [legacy]);
    let fresh!: (rows: api.WebhookRepoView[]) => void;
    vi.spyOn(api, 'fetchWebhookRepos').mockReturnValue(new Promise(done => { fresh = done; }));
    const update = vi.spyOn(api, 'updateWebhookRepo').mockResolvedValue({ ...legacy, enabled: false });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/settings/webhooks?edit=TEST-legacy']}>
      <SettingsWebhookRepos /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findAllByText('TEST-org')).not.toHaveLength(0);
    expect(screen.queryByRole('textbox', { name: 'Forge origin' })).toBeNull();
    await act(async () => fresh([{ ...legacy, enabled: false }]));
    fireEvent.change(await screen.findByRole('textbox', { name: 'Forge origin' }), { target: { value: 'https://api.github.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(update).toHaveBeenCalledWith(legacy.id, expect.objectContaining({ enabled: false })));
  });
});
