import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import { MemoryRouter } from 'react-router';
import * as api from '../api';

const legacy: api.WebhookRepoView = { id: 'TEST-legacy', providerType: 'github', scope: 'org', target: 'TEST-org',
  webhookKey: 'TEST-preserved-key', hasSecret: true, enabled: true, createdAt: '', forgeOrigin: null, eventKind: 'REVIEWER' };
const renderPage = (route = '/settings/webhooks') => render(<MemoryRouter initialEntries={[route]}><SettingsWebhookRepos /></MemoryRouter>);

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
