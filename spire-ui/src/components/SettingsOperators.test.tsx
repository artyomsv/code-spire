import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { SettingsOperators } from './SettingsOperators';
import * as api from '../api';

/**
 * The operator-to-SCM mapping screen (P4 / FR-11).
 *
 * <p>This screen creates the link that decides whose performance data someone sees, so the property
 * worth pinning is that the platform is always part of it. A mapping without one would match no
 * review at all, leaving the operator permanently unlinked with nothing explaining why — and a
 * mapping to the *wrong* platform would point at a different human, since the same SCM user id on
 * two platforms is two unrelated people.
 */

describe('SettingsOperators', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('lists the existing mappings with their platform', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([
      { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: 'TEST-AUTHOR-1' },
    ]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText('TEST-SUBJECT-1')).toBeTruthy());
    // Scoped to the row: 'github' also appears as an option in the platform picker above, and a
    // bare getByText would pass on the form alone -- proving nothing about the listing.
    const row = screen.getByText('TEST-SUBJECT-1').closest('tr') as HTMLElement;
    expect(within(row).getByText('github')).toBeTruthy();
    expect(within(row).getByText('TEST-AUTHOR-1')).toBeTruthy();
  });

  /** With nobody linked, self-visible analytics is dark for everyone — say so, don't show blank. */
  it('says what an empty list means', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText(/No operator is linked yet/)).toBeTruthy());
    expect(screen.getByText(/nobody can see their own activity/)).toBeTruthy();
  });

  it('always sends a platform with the mapping', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    const link = vi.spyOn(api, 'linkOperatorIdentity').mockResolvedValue(undefined);

    render(<SettingsOperators />);
    await waitFor(() => expect(screen.getByText('Link')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/Operator id/), { target: { value: 'TEST-SUBJECT-9' } });
    fireEvent.change(screen.getByLabelText(/SCM user id/), { target: { value: 'TEST-AUTHOR-9' } });
    fireEvent.click(screen.getByText('Link'));

    await waitFor(() =>
      expect(link).toHaveBeenCalledWith({
        oidcSubject: 'TEST-SUBJECT-9',
        providerType: 'github',
        authorId: 'TEST-AUTHOR-9',
      }),
    );
  });

  it('surfaces a rejected mapping instead of appearing to have saved it', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);
    vi.spyOn(api, 'linkOperatorIdentity').mockRejectedValue(new Error('TEST-REJECTED'));

    render(<SettingsOperators />);
    await waitFor(() => expect(screen.getByText('Link')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/Operator id/), { target: { value: 'TEST-SUBJECT-9' } });
    fireEvent.change(screen.getByLabelText(/SCM user id/), { target: { value: 'TEST-AUTHOR-9' } });
    fireEvent.click(screen.getByText('Link'));

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/TEST-REJECTED/));
  });

  /**
   * There is no "match by username" affordance, and there must never be one: a coincidental match
   * shows one person another person's performance data with nothing on screen looking wrong.
   */
  it('offers no way to guess the mapping from a username', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByText('Link')).toBeTruthy());
    expect(screen.queryByText(/match/i)).toBeNull();
    expect(screen.queryByText(/auto/i)).toBeNull();
    expect(screen.getByText(/never guessed from a username/i)).toBeTruthy();
  });
});
