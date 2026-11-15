import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { SettingsOperators } from './SettingsOperators';
import * as api from '../api';
import { queryWrapper } from '../test/queryWrapper';

/**
 * Which SCM accounts belong to which operator (P4 / FR-11).
 *
 * <p>The property worth pinning is that <b>both ends are picked, never typed</b>. The first version
 * asked for an OIDC subject and a stable provider id — two opaque values the product displays
 * nowhere, so the only way to fill the form was to query the database, while both had already been
 * recorded by ordinary use: subjects by every sign-in, author ids by every review.
 *
 * <p>The form is the repair path. The normal route is an operator signing into the platform, which
 * is proof rather than an assertion — covered by `ConnectOptions.test.tsx`.
 */

const AUTHORS: api.ObservedAuthor[] = [
  { providerType: 'github', authorId: '3218389', displayName: 'TEST-AUTHOR-A', reviews: 21 },
  { providerType: 'gitlab', authorId: '40124851', displayName: 'TEST-AUTHOR-A', reviews: 5 },
];

const OPERATORS: api.SeenOperator[] = [
  { subject: 'TEST-SUBJECT-1', username: 'test-user-one', displayName: 'TEST Operator One' },
  { subject: 'TEST-SUBJECT-9', username: 'test-user-nine', displayName: 'TEST Operator Nine' },
];

describe('SettingsOperators', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'fetchOperatorCandidates').mockResolvedValue(AUTHORS);
    vi.spyOn(api, 'fetchSeenOperators').mockResolvedValue(OPERATORS);
    // The sign-in card renders above the form and loads on its own; this screen's tests are not
    // about it, so it is answered rather than left to reject into a shared error banner.
    vi.spyOn(api, 'fetchScmOAuthApps').mockResolvedValue([]);
  });

  it('shows the links at once when the page opens again', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '3218389' },
    ]);
    const wrapper = queryWrapper();
    const first = render(<SettingsOperators />, { wrapper });
    expect(await screen.findByText('TEST-SUBJECT-1')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.fetchOperatorIdentities).mockReturnValue(new Promise(() => {}));
    render(<SettingsOperators />, { wrapper });
    expect(screen.getByText('TEST-SUBJECT-1')).toBeInTheDocument();
  });

  /** The screen is the People tab of Accounts, and says so above its own content. */
  it('renders as the People tab of Accounts', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getByRole('link', { name: 'People' })).toHaveAttribute('aria-current', 'page'));
    expect(screen.getByRole('link', { name: 'Machine accounts' })).toHaveAttribute('href', '#/settings/accounts');
  });

  it('lists the existing mappings with the operator and the name the reviews recorded', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '3218389' },
    ]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getByText('TEST-SUBJECT-1')).toBeTruthy());
    const row = screen.getByText('TEST-SUBJECT-1').closest('tr') as HTMLElement;
    // Both sides show a name, not just an opaque id -- the ids alone mean nothing to a reader.
    expect(within(row).getByText(/TEST Operator One/)).toBeTruthy();
    expect(within(row).getByText('TEST-AUTHOR-A')).toBeTruthy();
    expect(within(row).getByText('3218389')).toBeTruthy();
    expect(within(row).getByText('github')).toBeTruthy();
  });

  /**
   * The whole point of the redesign. If either field ever becomes free text again, the screen goes
   * back to being unusable without a database session.
   */
  it('offers both ends as choices rather than text fields', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getByLabelText(/SCM account/)).toBeTruthy());
    const account = screen.getByLabelText(/SCM account/) as HTMLSelectElement;
    const operator = screen.getByLabelText(/^Operator$/) as HTMLSelectElement;
    expect(account.tagName).toBe('SELECT');
    expect(operator.tagName).toBe('SELECT');
    // Each option names the person and how much the deployment has seen of them.
    expect(within(account).getByText(/TEST-AUTHOR-A · github · 21 reviews/)).toBeTruthy();
    expect(within(operator).getByText(/TEST Operator One · test-user-one/)).toBeTruthy();
  });

  it('sends the subject and the account that were picked', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    const link = vi.spyOn(api, 'linkOperatorIdentity').mockResolvedValue(undefined);

    const { container } = render(<SettingsOperators />, { wrapper: queryWrapper() });
    // Wait for the OPTIONS, not the button: the button is always there, and changing a select
    // before its options exist leaves the value at '' -- the browser refuses a value it has no
    // option for.
    await waitFor(() => expect(screen.getByText(/TEST-AUTHOR-A · gitlab/)).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/^Operator$/), { target: { value: 'TEST-SUBJECT-9' } });
    fireEvent.change(screen.getByLabelText(/SCM account/), { target: { value: 'gitlab|40124851' } });
    fireEvent.submit(container.querySelector('form.op-form') as HTMLFormElement);

    await waitFor(() =>
      expect(link).toHaveBeenCalledWith({
        oidcSubject: 'TEST-SUBJECT-9',
        providerType: 'gitlab',
        authorId: '40124851',
      }),
    );
  });

  /**
   * One human owns several accounts — the same developer is a GitHub id AND a GitLab id. Each is
   * linked separately, and the screen must not imply an operator has only one.
   */
  it('lets one operator hold several accounts', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '3218389' },
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'gitlab', authorId: '40124851' },
    ]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getAllByText('TEST-SUBJECT-1').length).toBe(2));
  });

  /** First production install: the account read `3218389` over `3218389` with no review yet. */
  it('shows the login a sign-in observed, with the id beneath', async () => {
    vi.spyOn(api, 'fetchOperatorCandidates').mockResolvedValue([]);
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '900001', authorHandle: 'TEST-login' },
    ]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    const row = (await screen.findByText('TEST-login')).closest('tr') as HTMLElement;
    expect(within(row).getByText('900001')).toBeTruthy();
  });

  /** A link made by hand has no observed login and no review names it: the id once, not twice. */
  it('shows the id once when nothing names the account', async () => {
    vi.spyOn(api, 'fetchOperatorCandidates').mockResolvedValue([]);
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '900001', authorHandle: null },
    ]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getAllByText('900001').length).toBe(1));
  });

  /** An operator whose login was an email has an empty username: the name alone, no trailing separator. */
  it('names an operator with no username by their display name', async () => {
    vi.spyOn(api, 'fetchSeenOperators').mockResolvedValue([
      { subject: 'TEST-SUBJECT-7', username: '', displayName: 'TEST Operator Seven' },
    ]);
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-7', providerType: 'github', authorId: '3218389' },
    ]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    const row = (await screen.findByText('TEST-SUBJECT-7')).closest('tr') as HTMLElement;
    expect(within(row).getByText('TEST Operator Seven')).toBeTruthy();
    expect(screen.getByRole('option', { name: 'TEST Operator Seven' })).toBeTruthy();
  });

  /** An operator never seen here is shown by subject, once. */
  it('shows an unseen operator subject once', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-UNSEEN', providerType: 'github', authorId: '3218389' },
    ]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getAllByText('TEST-SUBJECT-UNSEEN').length).toBe(1));
  });

  it('says what an empty list means', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getByText(/No operator is linked yet/)).toBeTruthy());
    expect(screen.getByText(/nobody can see their own activity/)).toBeTruthy();
  });

  /** Two different empty states, because they send an admin to two different places. */
  it('explains that nothing can be linked until a review has run', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'fetchOperatorCandidates').mockResolvedValue([]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getByText(/No author has been reviewed yet/)).toBeTruthy());
    expect((screen.getByText('Link') as HTMLButtonElement).disabled).toBe(true);
  });

  it('explains that nothing can be linked until somebody has signed in', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'fetchSeenOperators').mockResolvedValue([]);

    render(<SettingsOperators />, { wrapper: queryWrapper() });

    await waitFor(() => expect(screen.getByText(/Nobody has signed in yet/)).toBeTruthy());
    expect((screen.getByText('Link') as HTMLButtonElement).disabled).toBe(true);
  });

  it('surfaces a rejected mapping instead of appearing to have saved it', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'linkOperatorIdentity').mockRejectedValue(new Error('TEST-REJECTED'));

    const { container } = render(<SettingsOperators />, { wrapper: queryWrapper() });
    await waitFor(() => expect(screen.getByText(/TEST-AUTHOR-A · gitlab/)).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/^Operator$/), { target: { value: 'TEST-SUBJECT-9' } });
    fireEvent.change(screen.getByLabelText(/SCM account/), { target: { value: 'github|3218389' } });
    fireEvent.submit(container.querySelector('form.op-form') as HTMLFormElement);

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/TEST-REJECTED/));
  });
});
