import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { SettingsOperators } from './SettingsOperators';
import * as api from '../api';

/**
 * The operator-to-SCM mapping screen (P4 / FR-11).
 *
 * <p>The property worth pinning is that the SCM account is <b>picked from what the deployment has
 * actually reviewed</b>, never typed. The first version asked an admin to enter a stable provider id
 * such as `3218389` — a value the product displays nowhere, so the only way to fill the field was to
 * query the database, while every one of those ids had already been recorded dozens of times by the
 * reviews themselves.
 *
 * <p>A human still decides WHICH author is which operator. That part is deliberate: a coincidental
 * username match would show one person another person's performance data with nothing on screen
 * looking wrong. The product supplies the choices; an admin asserts the link.
 */

const AUTHORS: api.ObservedAuthor[] = [
  { providerType: 'github', authorId: '3218389', displayName: 'TEST-AUTHOR-A', reviews: 21 },
  { providerType: 'gitlab', authorId: '40124851', displayName: 'TEST-AUTHOR-A', reviews: 5 },
];

describe('SettingsOperators', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'fetchOperatorCandidates').mockResolvedValue(AUTHORS);
  });

  it('lists the existing mappings with their platform and the name the reviews recorded', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '3218389' },
    ]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText('TEST-SUBJECT-1')).toBeTruthy());
    const row = screen.getByText('TEST-SUBJECT-1').closest('tr') as HTMLElement;
    // The display name, not just the opaque id — the id alone means nothing to a reader.
    expect(within(row).getByText('TEST-AUTHOR-A')).toBeTruthy();
    expect(within(row).getByText('3218389')).toBeTruthy();
    expect(within(row).getByText('github')).toBeTruthy();
  });

  /**
   * The whole point of the redesign: an admin chooses an account the system has seen. If this ever
   * becomes a free-text field again, the screen goes back to being unusable without a database.
   */
  it('offers the reviewed authors as choices rather than a text field', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByLabelText(/SCM account/)).toBeTruthy());
    const picker = screen.getByLabelText(/SCM account/) as HTMLSelectElement;
    expect(picker.tagName).toBe('SELECT');
    // Each option names the person and how much the deployment has seen of them.
    expect(within(picker).getByText(/TEST-AUTHOR-A · github · 21 reviews/)).toBeTruthy();
    expect(within(picker).getByText(/TEST-AUTHOR-A · gitlab · 5 reviews/)).toBeTruthy();
  });

  it('sends the platform and id of the account that was picked', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    const link = vi.spyOn(api, 'linkOperatorIdentity').mockResolvedValue(undefined);

    const { container } = render(<SettingsOperators />);
    // Wait for the OPTIONS, not the button: the button is always there, and changing a select
    // before its options exist leaves the value at '' -- the browser refuses a value it has no option for.
    await waitFor(() => expect(screen.getByText(/TEST-AUTHOR-A · gitlab/)).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/Operator id/), { target: { value: 'TEST-SUBJECT-9' } });
    fireEvent.change(screen.getByLabelText(/SCM account/), { target: { value: 'gitlab|40124851' } });
    fireEvent.submit(container.querySelector('form') as HTMLFormElement);

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

  /** Before any review has run there is nobody to pick, and the screen says so rather than showing an empty menu. */
  it('explains that nothing can be linked until a review has run', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'fetchOperatorCandidates').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText(/No author has been reviewed yet/)).toBeTruthy());
    expect((screen.getByText('Link') as HTMLButtonElement).disabled).toBe(true);
  });

  it('surfaces a rejected mapping instead of appearing to have saved it', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'linkOperatorIdentity').mockRejectedValue(new Error('TEST-REJECTED'));

    const { container } = render(<SettingsOperators />);
    // Wait for the OPTIONS, not the button: the button is always there, and changing a select
    // before its options exist leaves the value at '' -- the browser refuses a value it has no option for.
    await waitFor(() => expect(screen.getByText(/TEST-AUTHOR-A · gitlab/)).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/Operator id/), { target: { value: 'TEST-SUBJECT-9' } });
    fireEvent.change(screen.getByLabelText(/SCM account/), { target: { value: 'github|3218389' } });
    fireEvent.submit(container.querySelector('form') as HTMLFormElement);

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/TEST-REJECTED/));
  });
});
