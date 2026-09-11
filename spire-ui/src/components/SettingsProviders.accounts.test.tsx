import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
  // A stored check that PASSED on a date no live result would produce, so a tooltip that leaks the
  // stored standing onto a freshly-answered row can be told from one that does not.
  lastCheckAt: '2026-07-27T10:00:00Z',
  lastCheckOk: true,
  lastCheckError: null,
  usedBy: [over.role === 'FACTORY' ? 'Factory' : 'Reviewer'],
  ...over,
});

/** Wait for the named cell, then hand back the row it sits in. */
const rowNamed = async (name: string) => (await screen.findByText(name)).closest('tr') as HTMLElement;

describe('SettingsProviders — the Machine accounts list', () => {
  beforeEach(() => {
    // A connectivity result is NOT the account's identity: the check reports the login the token
    // authenticated as, and the Identity column reports the login the registry stored. They are
    // deliberately different strings here so an assertion on one cannot be satisfied by the other.
    vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'test-checked', detail: null });
  });

  it('manages Atlassian accounts here and derives usage and scope text from the account', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({ name: 'Site account', type: 'atlassian', role: 'CONTEXT', workspace: null, usedBy: ['Project tickets', 'Wiki pages'], reportedScopes: null })]);
    renderPage();
    const row = await rowNamed('Site account');
    expect(within(row).getByText('Project tickets, Wiki pages')).toBeInTheDocument();
    expect(within(row).getByText('This token kind does not report its scopes')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Manage on Context' })).not.toBeInTheDocument();
  });

  it('shows reported scopes as neutral text and unused accounts with an empty usage cell', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({ role: 'CONTEXT', usedBy: [], reportedScopes: 'repo, read:org' })]);
    renderPage();
    const row = await rowNamed('TEST reviewer');
    expect(within(row).getByText('Token reports: repo, read:org')).toHaveClass('account-scopes');
    expect(row.querySelector('.account-uses')).toHaveTextContent('—');
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
    expect(within(row).getByLabelText('2 stable ids may command this bot')).toBeInTheDocument();
    // The number itself, on the visible surface: a tooltip assertion alone passed with the count deleted.
    expect(within(row).getByText('2')).toBeInTheDocument();
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
    // The dash belongs to the Policy cell and to no other. Counting dashes across the whole row
    // would also count an empty CopyableValue, which renders one and means something else entirely.
    const cells = within(row).getAllByRole('cell');
    expect(cells).toHaveLength(8);
    expect(cells[6]).toHaveTextContent('—');
    expect(within(cells[6]).queryByRole('img')).not.toBeInTheDocument();
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
    // Shown with the @ an operator recognises, copied without it: a forge's reviewer list and its
    // allowlist both want the bare handle, and CopyableValue's title is what the button copies.
    expect(within(row).getByText('@test-reviewer')).toHaveAttribute('title', 'test-reviewer');
  });

  /**
   * A disabled account is not an unknown one. The screen skips its automatic check, so there is no
   * live result — but the registry may hold a REJECTED one, and that used to be drawn as the grey
   * "never checked" icon with the rejection surviving only in a tooltip that contradicted it.
   */
  it('shows a disabled account with a rejected token as Failed, not as Not checked', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      forge({
        enabled: false,
        lastCheckAt: '2026-09-07T09:00:00Z',
        lastCheckOk: false,
        lastCheckError: 'Authentication rejected (HTTP 401)',
      }),
    ]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    const row = await rowNamed('TEST reviewer');
    const badge = within(row).getByRole('button', { name: 'Failed — check the connection' });
    expect(badge.getAttribute('title')).toContain('Authentication rejected (HTTP 401)');
    expect(within(row).queryByLabelText(/^Not checked/)).not.toBeInTheDocument();
    // Enabled left its column for a dot; the disabled half of that bit is the one nothing asserted.
    expect(within(row).getByLabelText('Disabled')).toBeInTheDocument();
  });

  /**
   * The other half of the same rule. A live result belongs to the account as it was when it
   * answered: disabling one that had just gone green must not leave the green behind, or the row
   * reports a credential nothing is using any more. Nothing re-checks a disabled account, so only
   * dropping the stale result can bring the row back to what the registry stored.
   */
  it('drops a live check result when the account it described comes back disabled', async () => {
    const list = vi
      .spyOn(api, 'fetchProviders')
      .mockResolvedValue([forge({}), forge({ id: 'TEST-p9', name: 'TEST spare' })]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    vi.spyOn(api, 'deleteProvider').mockResolvedValue(undefined as never);
    renderPage();

    const row = await rowNamed('TEST reviewer');
    await within(row).findByRole('button', { name: 'OK — check the connection' });

    // Deleting the spare reloads the list — and the first account comes back disabled and refused.
    list.mockResolvedValue([
      forge({ enabled: false, lastCheckOk: false, lastCheckError: 'Authentication rejected (HTTP 401)' }),
    ]);
    fireEvent.click(within(await rowNamed('TEST spare')).getByRole('button', { name: 'Delete' }));
    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    });

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Failed — check the connection' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'OK — check the connection' })).not.toBeInTheDocument();
  });

  /** No level set means the account follows the global default, and the cell has no room to say so twice. */
  it('says Inherit when no conversation level is set on the account', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({ conversationLevel: null })]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    expect(within(await rowNamed('TEST reviewer')).getByText('Inherit')).toBeInTheDocument();
  });

  /** A tracker that answered is the state no fixture covered — only its refusal and its silence were. */

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
    // The name says the action too: this is a button, and "OK" alone tells a screen-reader user
    // nothing about what pressing it does.
    const badge = await within(row).findByRole('button', { name: 'OK — check the connection' });
    expect(badge.getAttribute('title')).toContain('Connected as @test-checked');
    expect(badge.getAttribute('title')).toContain('click to check');
    // The login is on the tooltip and NOT in the column: that is the whole width saving. Nor is
    // the state word — the badge is an icon, and the word is its label and the head of its tooltip.
    expect(within(row).queryByText('@test-checked')).not.toBeInTheDocument();
    expect(within(row).queryByText('OK')).not.toBeInTheDocument();
    // And not the stored date either: the live answer has just replaced it, so repeating it in the
    // same tooltip would date a check that happened a second ago to whenever the last one did.
    expect(badge.getAttribute('title')).not.toContain('2026');
  });

  /**
   * A tracker is registered and checked on Context, so its badge reports the stored standing and
   * is not a control. A refusal can be a paragraph, and it is the tooltip that carries it.
   */

  /** Tracker accounts are listed so one screen answers "who acts as what"; they are edited on Context. */

  /** The forge list is the one that matters; a tracker fetch failing must not blank it. */

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

  /**
   * The same rule in the gap between the two fetches. Clearing the loading flag when the forge list
   * answered rendered a page with no forge rows and no trackers yet — indistinguishable from having
   * neither — so a deployment holding only tracker accounts flashed the empty state for one render.
   * The tracker fetch is left unresolved here, which IS that gap, held open.
   */
});
