import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import * as api from '../api';

const renderPage = () => render(<MemoryRouter><SettingsWebhookRepos /></MemoryRouter>);

const repo = (over: Partial<api.WebhookRepoView>): api.WebhookRepoView => ({
  id: 'TEST-w1',
  repositoryId: 'TEST-repository',
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

    expect(await screen.findByRole('heading', { name: 'Webhooks' })).toBeInTheDocument();
    const row = await rowFor('TEST-acme/widgets');
    const reviewer = await within(row).findByText(/reviewer-bot/);
    const factory = within(row).getByText(/factory-bot/);
    expect(reviewer.closest('.pill')).toHaveClass('completed');
    expect(factory.closest('.pill')).toHaveClass('refused');

    // Both roles share one column, and Secret has none: it read "secret set" on every healthy row.
    expect(screen.getByRole('columnheader', { name: 'Accounts' })).toBeInTheDocument();
    for (const gone of ['Secret', 'Reviewed by', 'Pushed by']) {
      expect(screen.queryByRole('columnheader', { name: gone })).not.toBeInTheDocument();
    }
  });

  /**
   * The one case the dropped Secret column was carrying. A registration with no secret accepts no
   * delivery, so it is said on the row rather than left to the attention panel alone.
   */
  it('says so under the target when a registration has no secret', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({ hasSecret: false })]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    expect(within(row).getByText('no secret')).toBeInTheDocument();
  });

  /** The healthy row says nothing about its secret — that noise is why the column went. */
  it('says nothing about the secret when one is stored', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({ hasSecret: true })]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    expect(within(row).queryByText(/secret/i)).not.toBeInTheDocument();
  });

  it('says none when no account serves a role, in grey', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    const none = await within(row).findByText(/Factory · none/);
    expect(none.closest('.pill')).toHaveClass('cancelled');
    // The chip stands alone. A Verify here named neither what it probed nor what a pass proved,
    // and beside the factory chip it read as "can push" — which its read-only GET never showed.
    const cell = none.closest('td') as HTMLElement;
    expect(within(cell).queryByRole('button')).not.toBeInTheDocument();
  });

  it('renders a state it does not know as unknown, never green', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving({ ...account('ok', 'reviewer-bot'), state: 'brand-new' as api.ServingState }, account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    const chip = await within(row).findByText(/unknown \(brand-new\)/);
    expect(chip.closest('.pill')).toHaveClass('cancelled');
  });

  it('renders a failed lookup as unknown rather than as none', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockRejectedValue(new Error('Failed to load the accounts serving this workspace'));
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    expect(await within(row).findAllByText(/unknown/)).toHaveLength(2);
    expect(within(row).queryByText(/none/)).not.toBeInTheDocument();
  });
});
