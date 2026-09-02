import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { SettingsOperators } from './SettingsOperators';
import * as api from '../api';

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

  it('lists the existing mappings with the operator and the name the reviews recorded', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '3218389' },
    ]);

    render(<SettingsOperators />);

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

    render(<SettingsOperators />);

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

    const { container } = render(<SettingsOperators />);
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

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getAllByText('TEST-SUBJECT-1').length).toBe(2));
  });

  it('says what an empty list means', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText(/No operator is linked yet/)).toBeTruthy());
    expect(screen.getByText(/nobody can see their own activity/)).toBeTruthy();
  });

  /** Two different empty states, because they send an admin to two different places. */
  it('explains that nothing can be linked until a review has run', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'fetchOperatorCandidates').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText(/No author has been reviewed yet/)).toBeTruthy());
    expect((screen.getByText('Link') as HTMLButtonElement).disabled).toBe(true);
  });

  it('explains that nothing can be linked until somebody has signed in', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'fetchSeenOperators').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText(/Nobody has signed in yet/)).toBeTruthy());
    expect((screen.getByText('Link') as HTMLButtonElement).disabled).toBe(true);
  });

  it('surfaces a rejected mapping instead of appearing to have saved it', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'linkOperatorIdentity').mockRejectedValue(new Error('TEST-REJECTED'));

    const { container } = render(<SettingsOperators />);
    await waitFor(() => expect(screen.getByText(/TEST-AUTHOR-A · gitlab/)).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/^Operator$/), { target: { value: 'TEST-SUBJECT-9' } });
    fireEvent.change(screen.getByLabelText(/SCM account/), { target: { value: 'github|3218389' } });
    fireEvent.submit(container.querySelector('form.op-form') as HTMLFormElement);

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/TEST-REJECTED/));
  });
});
