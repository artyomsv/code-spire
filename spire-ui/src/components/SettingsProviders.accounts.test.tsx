import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsProviders from './SettingsProviders';
import * as api from '../api';

const renderPage = () =>
  render(
    <MemoryRouter>
      <SettingsProviders />
    </MemoryRouter>,
  );

const forge = (over: Partial<api.ProviderView>): api.ProviderView => ({
  id: 'TEST-p1',
  name: 'TEST reviewer',
  type: 'github',
  baseUrl: 'https://api.github.com',
  workspace: 'TEST-acme',
  authKind: 'bearer',
  authUsername: null,
  hasSecret: true,
  botAccountId: 'TEST-acct-1',
  botUsername: 'test-reviewer',
  enabled: true,
  authors: ['TEST-1', 'TEST-2'],
  conversationLevel: 'EXPLAIN',
  role: 'REVIEWER',
  createdAt: '2026-09-07T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
  ...over,
});

const tracker: api.ContextProviderView = {
  id: 'TEST-c1',
  name: 'TEST Jira',
  type: 'jira',
  baseUrl: 'https://test-acme.atlassian.net',
  authKind: 'basic',
  username: 'jira-bot@example.invalid',
  projectKeys: null,
  hasSecret: true,
  enabled: true,
  isDefault: false,
  createdAt: '2026-09-07T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
};

/** Wait for the named cell, then hand back the row it sits in. */
const rowNamed = async (name: string) => (await screen.findByText(name)).closest('tr') as HTMLElement;

describe('SettingsProviders — the Machine accounts list', () => {
  beforeEach(() => {
    // A connectivity result is NOT the account's identity: the check reports the login the token
    // authenticated as, and the Identity column reports the login the registry stored. They are
    // deliberately different strings here so an assertion on one cannot be satisfied by the other.
    vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'test-checked', detail: null });
  });

  it('is titled Accounts and shows a forge row with its kind, role, identity and scope', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Accounts' })).toBeInTheDocument();
    const row = await rowNamed('TEST reviewer');
    expect(within(row).getByText('Forge · github')).toBeInTheDocument();
    expect(within(row).getByText('Reviewer')).toBeInTheDocument();
    expect(within(row).getByText('@test-reviewer')).toBeInTheDocument();
    expect(within(row).getByText('TEST-acme')).toBeInTheDocument();
    expect(within(row).getByText('2')).toBeInTheDocument(); // May command: two ids listed
    expect(within(row).getByText('Explain')).toBeInTheDocument();
  });

  /** A factory account has no allowlist and no conversation: those are the reviewer's job. */
  it('shows a factory row with dashes where the reviewer-only columns are', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      forge({ id: 'TEST-p2', name: 'TEST factory', role: 'FACTORY', botUsername: 'test-factory', authors: [] }),
    ]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    const row = await rowNamed('TEST factory');
    expect(within(row).getByText('Factory')).toBeInTheDocument();
    expect(within(row).getByText('@test-factory')).toBeInTheDocument();
    expect(within(row).getAllByText('—')).toHaveLength(2);
  });

  it('says when the identity is not resolved rather than showing nothing', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({ botUsername: null, botAccountId: '' })]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    expect(within(await rowNamed('TEST reviewer')).getByText('not resolved')).toBeInTheDocument();
  });

  /** Tracker accounts are listed so one screen answers "who acts as what"; they are edited on Context. */
  it('lists tracker accounts read-only, after the forge rows, with a link to manage them on Context', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([tracker]);
    renderPage();

    const row = await rowNamed('TEST Jira');
    expect(within(row).getByText('Tracker · jira')).toBeInTheDocument();
    expect(within(row).getByText('Read')).toBeInTheDocument();
    expect(within(row).getByText('jira-bot@example.invalid')).toBeInTheDocument();
    expect(within(row).getByText('test-acme.atlassian.net')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'Manage on Context' })).toHaveAttribute(
      'href',
      '#/settings/context?edit=TEST-c1',
    );
    expect(within(row).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();

    const rows = screen.getAllByRole('row').slice(1); // drop the header
    expect(rows[0]).toHaveTextContent('TEST reviewer');
    expect(rows[1]).toHaveTextContent('TEST Jira');
  });

  /** The forge list is the one that matters; a tracker fetch failing must not blank it. */
  it('keeps the forge rows when the tracker list cannot be loaded, and says so', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockRejectedValue(new Error('Failed to load context providers'));
    renderPage();

    expect(await screen.findByText('TEST reviewer')).toBeInTheDocument();
    expect(await screen.findByText(/tracker accounts could not be loaded/i)).toBeInTheDocument();
  });

  it('shows the empty state only when there is nothing of either kind, and offers Add account', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/no machine accounts yet/i)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /add account/i }).length).toBeGreaterThanOrEqual(1);
    await waitFor(() => expect(screen.queryByRole('button', { name: /add provider/i })).not.toBeInTheDocument());
  });

  /**
   * The discriminating half of the rule above. A deployment that reads trackers but has registered
   * no forge account yet has something to show, and an empty state over a populated table would
   * hide it — so the count of BOTH kinds decides, not the forge count alone.
   */
  it('does not show the empty state when only tracker accounts exist', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([tracker]);
    renderPage();

    expect(await screen.findByText('TEST Jira')).toBeInTheDocument();
    expect(screen.queryByText(/no machine accounts yet/i)).not.toBeInTheDocument();
  });

  /**
   * The same rule in the gap between the two fetches. Clearing the loading flag when the forge list
   * answered rendered a page with no forge rows and no trackers yet — indistinguishable from having
   * neither — so a deployment holding only tracker accounts flashed the empty state for one render.
   * The tracker fetch is left unresolved here, which IS that gap, held open.
   */
  it('keeps showing Loading until the tracker list answers too', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchContextProviders').mockReturnValue(new Promise<api.ContextProviderView[]>(() => {}));
    renderPage();

    // The forge fetch has answered by the time the tracker fetch is issued, so this is the gap —
    // asserted rather than slept for, since "Loading…" is also the state before anything resolved.
    await waitFor(() => expect(api.fetchContextProviders).toHaveBeenCalled());
    await act(async () => {});

    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText(/no machine accounts yet/i)).not.toBeInTheDocument();
  });
});
