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
    // The kind is an icon; the word it stands for is its accessible name, which is what makes
    // dropping the word from the cell safe. Asserting the label rather than the glyph.
    expect(within(row).getByLabelText('Forge account')).toBeInTheDocument();
    expect(within(row).getByText('github')).toBeInTheDocument();
    expect(within(row).getByText('Reviewer')).toBeInTheDocument();
    expect(within(row).getByText('@test-reviewer')).toBeInTheDocument();
    expect(within(row).getByText('TEST-acme')).toBeInTheDocument();
    // Policy is one cell: how many ids may command this bot, and how far it converses. The count
    // wears a head-count icon rather than the word "ids", so the sentence is on its tooltip.
    expect(within(row).getByTitle('2 stable ids may command this bot')).toBeInTheDocument();
    expect(within(row).getByText('Explain')).toBeInTheDocument();
    // Enabled left its column for a dot beside the name. A colour with no name says nothing, so
    // the word is the dot's accessible label and this is the assertion that keeps it there.
    expect(within(row).getByLabelText('Enabled')).toBeInTheDocument();
  });

  /** A factory account has no allowlist and no conversation: those are the reviewer's job. */
  it('shows a factory row with one dash, where the reviewer-only Policy cell is', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      forge({ id: 'TEST-p2', name: 'TEST factory', role: 'FACTORY', botUsername: 'test-factory', authors: [] }),
    ]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    const row = await rowNamed('TEST factory');
    expect(within(row).getByText('Factory')).toBeInTheDocument();
    expect(within(row).getByText('@test-factory')).toBeInTheDocument();
    // Exactly one: the two reviewer-only columns became one Policy cell, so two dashes here would
    // mean a column that was meant to be gone is still being rendered.
    expect(within(row).getAllByText('—')).toHaveLength(1);
    expect(within(row).getByLabelText('Enabled')).toBeInTheDocument();
  });

  it('says when the identity is not resolved rather than showing nothing', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({ botUsername: null, botAccountId: '' })]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    const row = await rowNamed('TEST reviewer');
    expect(within(row).getByText('not resolved')).toBeInTheDocument();
    // Two words are not a value. A copy button here would hand the operator "not resolved".
    expect(within(row).queryByRole('button', { name: /^Copy the identity$/ })).not.toBeInTheDocument();
  });

  /**
   * The three long values are pasted into other people's portals — a forge's reviewer list, a
   * webhook form. Truncating them without a copy button would take them away rather than shorten
   * them, so each carries the WHOLE value: on the copy button and in the tooltip beside it.
   */
  it('offers the whole base URL, identity and workspace to copy, however narrow the cell', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      forge({ baseUrl: 'https://api.github.com/very/long/base/url/that/will/not/fit' }),
    ]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    const row = await rowNamed('TEST reviewer');
    expect(within(row).getByRole('button', { name: 'Copy the base URL' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Copy the identity' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Copy the workspace' })).toBeInTheDocument();
    expect(within(row).getByText('https://api.github.com/very/long/base/url/that/will/not/fit')).toHaveAttribute(
      'title',
      'https://api.github.com/very/long/base/url/that/will/not/fit',
    );
  });

  /**
   * The connection is one badge in four states. Who the token authenticated as is variable-length
   * text that sat in the column on every row; it is on the hover now, and the badge says only which
   * of the four states this account is in.
   */
  it('reports a connected account as one OK badge, naming the login it connected as on the hover', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    const row = await rowNamed('TEST reviewer');
    const badge = await within(row).findByRole('button', { name: 'OK' });
    expect(badge.getAttribute('title')).toContain('Connected as @test-checked');
    expect(badge.getAttribute('title')).toContain('click to re-check');
    // The login is on the tooltip and NOT in the column: that is the whole width saving. Nor is
    // the state word — the badge is an icon, and the word is its label and the head of its tooltip.
    expect(within(row).queryByText('@test-checked')).not.toBeInTheDocument();
    expect(within(row).queryByText('OK')).not.toBeInTheDocument();
  });

  /**
   * A tracker is registered and checked on Context, so its badge reports the stored standing and
   * is not a control. A refusal can be a paragraph, and it is the tooltip that carries it.
   */
  it('shows a refused tracker as a Failed badge that cannot be clicked, with the reason on the hover', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([
      {
        ...tracker,
        lastCheckAt: '2026-09-07T09:00:00Z',
        lastCheckOk: false,
        lastCheckError: 'Authentication failed (HTTP 401)',
      },
    ]);
    renderPage();

    const row = await rowNamed('TEST Jira');
    // The state is an icon. Its word is the accessible name — which is what makes dropping the
    // visible label safe — and the provider's own message is on the tooltip behind it.
    const badge = within(row).getByLabelText('Failed');
    expect(badge.getAttribute('title')).toContain('Authentication failed (HTTP 401)');
    expect(within(row).queryByRole('button', { name: 'Failed' })).not.toBeInTheDocument();
  });

  /** Tracker accounts are listed so one screen answers "who acts as what"; they are edited on Context. */
  it('lists tracker accounts read-only, after the forge rows, with a link to manage them on Context', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([tracker]);
    renderPage();

    const row = await rowNamed('TEST Jira');
    expect(within(row).getByLabelText('Tracker account')).toBeInTheDocument();
    expect(within(row).getByText('jira')).toBeInTheDocument();
    expect(within(row).getByText('Read')).toBeInTheDocument();
    expect(within(row).getByText('jira-bot@example.invalid')).toBeInTheDocument();
    expect(within(row).getByText('test-acme.atlassian.net')).toBeInTheDocument();
    // Never checked is information, not a problem — the fourth state of the same badge.
    expect(within(row).getByLabelText('Not checked')).toBeInTheDocument();
    expect(within(row).getByText('—')).toBeInTheDocument(); // Policy: a tracker commands nothing
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
