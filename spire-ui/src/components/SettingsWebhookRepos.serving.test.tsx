import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import * as api from '../api';

const renderPage = () => render(<MemoryRouter><SettingsWebhookRepos /></MemoryRouter>);

const repo = (over: Partial<api.WebhookRepoView>): api.WebhookRepoView => ({
  id: 'TEST-w1',
  providerType: 'github',
  scope: 'repo',
  target: 'TEST-acme/widgets',
  webhookKey: 'TEST-key',
  hasSecret: true,
  enabled: true,
  createdAt: '2026-09-07T00:00:00Z',
  ...over,
});

const account = (state: api.ServingState, name: string): api.ServingAccount => ({
  state,
  id: state === 'missing' ? null : `TEST-${name}`,
  name: state === 'missing' ? null : name,
  botUsername: null,
  botAccountId: null,
});

const serving = (reviewer: api.ServingAccount, factory: api.ServingAccount): api.ServingAccounts => ({
  type: 'github',
  workspace: 'TEST-acme',
  reviewer,
  factory,
});

const rowFor = async (target: string) => (await screen.findByText(target)).closest('tr') as HTMLElement;

describe('SettingsWebhookRepos — who reviews and who pushes', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
  });

  it('is titled Repositories and shows both roles for a row, green only when usable', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('no-login', 'factory-bot')),
    );
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Repositories' })).toBeInTheDocument();
    const row = await rowFor('TEST-acme/widgets');
    const reviewer = await within(row).findByText('reviewer-bot');
    const factory = within(row).getByText('factory-bot');
    expect(reviewer.closest('.pill')).toHaveClass('completed');
    expect(factory.closest('.pill')).toHaveClass('refused');
  });

  it('says none when no account serves a role, in grey', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    const none = await within(row).findByText('none');
    expect(none.closest('.pill')).toHaveClass('cancelled');
    expect(within(row).queryByRole('button', { name: /verify push account/i })).not.toBeInTheDocument();
  });

  it('renders a state it does not know as unknown, never green', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving({ ...account('ok', 'reviewer-bot'), state: 'brand-new' as api.ServingState }, account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    const chip = await within(row).findByText('unknown (brand-new)');
    expect(chip.closest('.pill')).toHaveClass('cancelled');
  });

  it('renders a failed lookup as unknown rather than as none', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockRejectedValue(new Error('Failed to load the accounts serving this workspace'));
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    expect(await within(row).findAllByText('unknown')).toHaveLength(2);
    expect(within(row).queryByText('none')).not.toBeInTheDocument();
  });

  /** The chip's own account is what gets verified — the factory's Verify must not probe with the reviewer's token. */
  it('verifies each role with that role’s account, and says push rights are not checked', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('ok', 'factory-bot')),
    );
    const verify = vi.spyOn(api, 'verifyRepo').mockResolvedValue({ ok: true, detail: null });
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    fireEvent.click(await within(row).findByRole('button', { name: /verify push account for TEST-acme\/widgets/i }));
    await waitFor(() => expect(verify).toHaveBeenCalledWith('TEST-factory-bot', 'TEST-acme/widgets'));
    expect(await within(row).findByText(/push rights are not checked/i)).toBeInTheDocument();

    fireEvent.click(within(row).getByRole('button', { name: /verify review account for TEST-acme\/widgets/i }));
    await waitFor(() => expect(verify).toHaveBeenCalledWith('TEST-reviewer-bot', 'TEST-acme/widgets'));
  });

  /** An organization row has no repository to GET; its verify is the account's own connectivity check. */
  it('checks the account itself for an organization row', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({ scope: 'org', target: 'TEST-acme' })]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('missing', '')),
    );
    const check = vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'reviewer-bot', detail: null });
    renderPage();

    const row = await rowFor('TEST-acme');
    fireEvent.click(await within(row).findByRole('button', { name: /verify review account for TEST-acme$/i }));
    await waitFor(() => expect(check).toHaveBeenCalledWith('TEST-reviewer-bot'));
  });
});
